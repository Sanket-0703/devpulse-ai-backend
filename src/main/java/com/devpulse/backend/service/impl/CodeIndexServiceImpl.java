package com.devpulse.backend.service.impl;

import com.devpulse.backend.client.GitHubApiClient;
import com.devpulse.backend.codeindex.CodeChunk;
import com.devpulse.backend.codeindex.CodeChunker;
import com.devpulse.backend.codeindex.SourceArchive;
import com.devpulse.backend.codeindex.SourceFile;
import com.devpulse.backend.common.RedisKeys;
import com.devpulse.backend.dto.github.RepositoryResponse;
import com.devpulse.backend.entity.GitHubRepository;
import com.devpulse.backend.entity.SyncStatus;
import com.devpulse.backend.exception.ConflictException;
import com.devpulse.backend.exception.GitHubIntegrationException;
import com.devpulse.backend.exception.ResourceNotFoundException;
import com.devpulse.backend.repository.CodeChunkRepository;
import com.devpulse.backend.repository.GitHubRepositoryRepository;
import com.devpulse.backend.service.CodeIndexService;
import com.devpulse.backend.service.EmbeddingService;
import com.devpulse.backend.service.GitHubInstallationTokenService;
import com.devpulse.backend.service.RedisService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * <pre>
 * default branch's head commit → zip of the repository at that commit (one download)
 *   → source files (code only, bounded) → chunks of ≤ 40 lines
 *   → embeddings (local model; cached in Redis by content hash)
 *   → replace the repository's rows in code_chunks (one transaction)
 * </pre>
 * Runs on the background executor; the repository's index_status says where it is.
 */
@Slf4j
@Service
public class CodeIndexServiceImpl implements CodeIndexService {

    /** A safety net if an index dies without releasing its lock. */
    static final Duration LOCK_TTL = Duration.ofMinutes(15);
    /** Even a big monorepo slice stays searchable; beyond this the rest is left out. */
    static final int MAX_CHUNKS = 20_000;
    static final String DEMO_COMMIT = "demo-source";

    private final GitHubRepositoryRepository repositoryRepository;
    private final CodeChunkRepository chunkRepository;
    private final EmbeddingService embeddingService;
    private final GitHubInstallationTokenService installationTokens;
    private final GitHubApiClient apiClient;
    private final RedisService redisService;
    private final ExecutorService backgroundExecutor;
    private final Resource demoSource;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public CodeIndexServiceImpl(GitHubRepositoryRepository repositoryRepository,
                                CodeChunkRepository chunkRepository,
                                EmbeddingService embeddingService,
                                GitHubInstallationTokenService installationTokens,
                                GitHubApiClient apiClient,
                                RedisService redisService,
                                ExecutorService backgroundExecutor,
                                @Value("classpath:demo/shop-api-source.zip") Resource demoSource,
                                PlatformTransactionManager transactionManager,
                                Clock clock) {
        this.repositoryRepository = repositoryRepository;
        this.chunkRepository = chunkRepository;
        this.embeddingService = embeddingService;
        this.installationTokens = installationTokens;
        this.apiClient = apiClient;
        this.redisService = redisService;
        this.backgroundExecutor = backgroundExecutor;
        this.demoSource = demoSource;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    // ---------- starting ----------

    @Override
    public RepositoryResponse requestIndex(Long userId, Long repositoryId) {
        transaction.executeWithoutResult(tx -> {
            GitHubRepository repository = repositoryRepository.findByIdAndGithubAccountUserId(repositoryId, userId)
                    .orElseThrow(() -> new ResourceNotFoundException("Repository not found"));
            if (repository.getGithubAccount().getInstallationId() == null) {
                throw new ConflictException("Install the DevPulse app on this repository first.");
            }
        });
        if (!start(repositoryId)) {
            throw new ConflictException("This repository is already being indexed.");
        }
        return transaction.execute(tx -> RepositoryResponse.from(repositoryRepository.findById(repositoryId)
                .orElseThrow()));
    }

    @Override
    public boolean start(long repositoryId) {
        String lockKey = RedisKeys.CODE_INDEX_LOCK_PREFIX + repositoryId;
        if (!redisService.setIfAbsent(lockKey, "1", LOCK_TTL)) {
            return false; // already running
        }
        transaction.executeWithoutResult(tx -> repositoryRepository.findById(repositoryId).ifPresent(repository -> {
            repository.setIndexStatus(SyncStatus.RUNNING);
            repository.setIndexError(null);
        }));
        backgroundExecutor.execute(() -> run(repositoryId, lockKey));
        return true;
    }

    // ---------- the job ----------

    /** What the job needs to know, read before any network call. */
    private record Target(long githubId, String defaultBranch, Long installationId, boolean demo) {
    }

    private void run(long repositoryId, String lockKey) {
        long started = System.nanoTime();
        Long installationId = null;
        try {
            Target target = transaction.execute(tx -> repositoryRepository.findById(repositoryId)
                    .map(r -> new Target(r.getGithubId(), r.getDefaultBranch(),
                            r.getGithubAccount().getInstallationId(), r.getGithubAccount().isDemo()))
                    .orElseThrow());
            installationId = target.installationId();

            String commit;
            byte[] zip;
            if (target.demo()) {
                commit = DEMO_COMMIT; // the demo's code ships with DevPulse
                zip = demoSource.getContentAsByteArray();
            } else {
                String token = installationTokens.getToken(target.installationId());
                commit = apiClient.getBranchHeadSha(token, target.githubId(), target.defaultBranch());
                zip = apiClient.downloadZipball(token, target.githubId(), commit);
            }

            List<SourceFile> files = SourceArchive.read(zip);
            List<CodeChunk> chunks = new ArrayList<>();
            for (SourceFile file : files) {
                chunks.addAll(CodeChunker.chunk(file));
            }
            if (chunks.size() > MAX_CHUNKS) {
                chunks = chunks.subList(0, MAX_CHUNKS);
            }

            List<String> texts = chunks.stream().map(CodeChunk::embeddingText).toList();
            EmbeddingService.Result embedded = embeddingService.embedAll(texts);
            List<CodeChunkRepository.NewChunk> rows = new ArrayList<>(chunks.size());
            for (int i = 0; i < chunks.size(); i++) {
                rows.add(new CodeChunkRepository.NewChunk(chunks.get(i), EmbeddingServiceImpl.sha256(texts.get(i)),
                        embedded.vectors().get(i)));
            }

            int chunkCount = rows.size();
            transaction.executeWithoutResult(tx -> {
                chunkRepository.replaceAll(repositoryId, rows);
                repositoryRepository.findById(repositoryId).ifPresent(repository -> {
                    repository.setIndexStatus(SyncStatus.SUCCEEDED);
                    repository.setIndexedAt(clock.instant());
                    repository.setIndexedCommitSha(commit);
                    repository.setIndexedFiles(files.size());
                    repository.setIndexedChunks(chunkCount);
                    repository.setIndexError(null);
                });
            });
            log.info("Indexed repository {}: {} files, {} chunks ({} embeddings from cache) in {} ms", repositoryId,
                    files.size(), chunkCount, embedded.fromCache(), (System.nanoTime() - started) / 1_000_000);

        } catch (GitHubIntegrationException e) {
            log.warn("Indexing repository {} failed: GitHub ({})", repositoryId, e.getReason());
            if (installationId != null) {
                installationTokens.invalidate(installationId);
            }
            fail(repositoryId, "Couldn't download the repository from GitHub. Please try again.");
        } catch (IOException | UncheckedIOException e) {
            log.warn("Indexing repository {} failed: unreadable archive", repositoryId, e);
            fail(repositoryId, "The repository's files couldn't be read.");
        } catch (RuntimeException e) {
            log.error("Indexing repository {} failed", repositoryId, e);
            fail(repositoryId, "Something went wrong while indexing. Please try again.");
        } finally {
            redisService.delete(lockKey);
        }
    }

    private void fail(long repositoryId, String message) {
        transaction.executeWithoutResult(tx -> repositoryRepository.findById(repositoryId).ifPresent(repository -> {
            repository.setIndexStatus(SyncStatus.FAILED);
            repository.setIndexError(message);
        }));
    }

    // A restart kills running jobs; don't leave them "indexing" forever.
    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.HIGHEST_PRECEDENCE + 1) // after StaleLockCleaner, before the demo seeder starts jobs
    public void failInterruptedIndexes() {
        Integer failed = transaction.execute(tx -> repositoryRepository.failRunningIndexes(
                "Indexing was interrupted by a server restart. Please index again."));
        if (failed != null && failed > 0) {
            log.info("Marked {} interrupted index job(s) as failed", failed);
        }
    }
}
