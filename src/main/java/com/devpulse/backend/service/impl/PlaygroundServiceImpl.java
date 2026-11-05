package com.devpulse.backend.service.impl;

import com.devpulse.backend.common.RedisKeys;
import com.devpulse.backend.config.AIProperties;
import com.devpulse.backend.dto.github.PullRequestFilesResponse.ChangedFile;
import com.devpulse.backend.dto.playground.PlaygroundFinding;
import com.devpulse.backend.dto.playground.PlaygroundRequest;
import com.devpulse.backend.dto.playground.PlaygroundResponse;
import com.devpulse.backend.entity.PlaygroundRun;
import com.devpulse.backend.exception.TooManyRequestsException;
import com.devpulse.backend.repository.PlaygroundRunRepository;
import com.devpulse.backend.repository.UserRepository;
import com.devpulse.backend.service.AIUsageService;
import com.devpulse.backend.service.PlaygroundService;
import com.devpulse.backend.service.RedisService;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * The snippet is reviewed as if it were a new file in a pull request
 * ("snippet.java", every line added), so it goes through exactly the same
 * engine: security rules, the AI model, validation of what the model says.
 *
 * <p>Hardening, compared with the old raw "POST /api/ai/review":
 * <ul>
 *   <li>a typed, validated request: a known language, 1–20,000 characters;</li>
 *   <li>a burst limit (per minute, a Redis counter) and a daily run limit;</li>
 *   <li>the same daily AI budget as reviews, and at most two AI requests per run;</li>
 *   <li>usage is recorded (tokens, cost) but the code itself is never stored.</li>
 * </ul>
 */
@Service
public class PlaygroundServiceImpl implements PlaygroundService {

    static final Duration RATE_WINDOW = Duration.ofMinutes(1);
    /** 20,000 characters fit in two 12,000-character chunks. */
    static final int MAX_CHUNKS = 2;

    private final ReviewEngine engine;
    private final AIUsageService usageService;
    private final PlaygroundRunRepository runRepository;
    private final UserRepository userRepository;
    private final RedisService redisService;
    private final AIProperties properties;

    public PlaygroundServiceImpl(ReviewEngine engine, AIUsageService usageService,
                                 PlaygroundRunRepository runRepository, UserRepository userRepository,
                                 RedisService redisService, AIProperties properties) {
        this.engine = engine;
        this.usageService = usageService;
        this.runRepository = runRepository;
        this.userRepository = userRepository;
        this.redisService = redisService;
        this.properties = properties;
    }

    @Override
    public PlaygroundResponse review(Long userId, PlaygroundRequest request) {
        enforceLimits(userId);

        String fileName = request.language().fileName();
        ReviewEngine.Analysis analysis = engine.analyze(List.of(asNewFile(fileName, request.code())), MAX_CHUNKS,
                properties.limits().maxReviewCostUsd());

        PlaygroundRun run = new PlaygroundRun();
        run.setUser(userRepository.getReferenceById(userId));
        run.setLanguage(request.language());
        run.setCodeChars(request.code().length());
        run.setFindings(analysis.findings().size());
        run.setProvider(engine.provider());
        run.setModel(engine.model());
        run.setPromptTokens(analysis.promptTokens());
        run.setCompletionTokens(analysis.completionTokens());
        run.setEstimatedCostUsd(analysis.cost());
        runRepository.save(run);

        return new PlaygroundResponse(analysis.findings().stream().map(PlaygroundFinding::from).toList(),
                engine.provider(), engine.model(), analysis.promptTokens(), analysis.completionTokens(),
                analysis.cost(), analysis.truncated());
    }

    private void enforceLimits(Long userId) {
        String rateKey = RedisKeys.PLAYGROUND_RATE_PREFIX + userId;
        if (redisService.increment(rateKey, RATE_WINDOW) > properties.limits().playgroundRunsPerMinute()) {
            Long ttl = redisService.getTtl(rateKey);
            throw new TooManyRequestsException("Slow down: at most " + properties.limits().playgroundRunsPerMinute()
                    + " Playground runs a minute.", ttl == null || ttl < 1 ? 1 : ttl);
        }
        if (runRepository.countByUserIdAndCreatedAtGreaterThanEqual(userId, usageService.startOfToday())
                >= properties.limits().dailyPlaygroundRuns()) {
            throw new TooManyRequestsException("You've used today's " + properties.limits().dailyPlaygroundRuns()
                    + " Playground runs. The limit resets at midnight UTC.", usageService.secondsUntilTomorrow());
        }
        usageService.enforceDailyBudget(userId);
    }

    /** The snippet as a diff that adds every line: line N of the snippet is line N of the "file". */
    static ChangedFile asNewFile(String fileName, String code) {
        String[] lines = code.replace("\r\n", "\n").split("\n", -1);
        StringBuilder patch = new StringBuilder("@@ -0,0 +1," + lines.length + " @@");
        for (String line : lines) {
            patch.append("\n+").append(line);
        }
        return new ChangedFile(fileName, null, "added", lines.length, 0, patch.toString(), null);
    }
}
