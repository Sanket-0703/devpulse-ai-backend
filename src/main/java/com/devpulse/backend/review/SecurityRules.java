package com.devpulse.backend.review;

import com.devpulse.backend.entity.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * DevPulse's own security checks (all category SECURITY), run on every added
 * line next to the AI.
 * Deterministic and free: they catch the well-known patterns every time,
 * while the AI covers what regular expressions can't.
 */
public final class SecurityRules {

    public record Rule(String id, Severity severity, String title, String description, String recommendation,
                       Pattern pattern) {
    }

    public record Match(Rule rule, String path, int line) {
    }

    // Built from parts so secret scanners don't take this source file for a key.
    private static final String PRIVATE_KEY = "PRIVATE" + " KEY";

    public static final List<Rule> RULES = List.of(
            new Rule("private-key", Severity.CRITICAL,
                    "Private key committed",
                    "A private key is being added to the repository. Anyone with read access can now use it.",
                    "Remove it, rotate the key, and load it from a secret store or an untracked file.",
                    Pattern.compile("-----BEGIN ((RSA|EC|DSA|OPENSSH) )?" + PRIVATE_KEY + "-----")),
            new Rule("aws-access-key", Severity.CRITICAL,
                    "AWS access key in code",
                    "This looks like an AWS access key id. Keys in code end up in git history and in every clone.",
                    "Revoke the key in AWS IAM and use environment variables or an IAM role instead.",
                    Pattern.compile("\\b(AKIA|ASIA)[0-9A-Z]{16}\\b")),
            new Rule("hardcoded-credential", Severity.HIGH,
                    "Hard-coded credential",
                    "A password, secret, key or token is written directly in the code.",
                    "Read it from configuration or a secret manager, and rotate the value if it was real.",
                    Pattern.compile("(?i)\\b\\w*(password|passwd|pwd|secret|api[_-]?key|access[_-]?token|auth[_-]?token)"
                            + "\\w*[\"']?\\s*[:=]\\s*[\"'](?!\\$\\{|<|\\*{3})[^\"'\\s]{4,}[\"']")),
            new Rule("sql-concatenation", Severity.HIGH,
                    "SQL built from strings",
                    "The SQL statement is assembled with string concatenation or interpolation. If any part comes "
                            + "from user input, this is an SQL injection.",
                    "Use a parameterized query (PreparedStatement, named parameters, or the ORM's query methods).",
                    // "SELECT … '" + email   |   `SELECT … ${id}`   |   f"SELECT … {id}"
                    Pattern.compile("(?i)[\"'`]\\s*(SELECT|INSERT\\s+INTO|UPDATE|DELETE\\s+FROM)\\b.*[\"'`]\\s*\\+\\s*\\w"
                            + "|(?i)\\b(SELECT\\s.+\\sFROM|INSERT\\s+INTO|UPDATE\\s+\\w+\\s+SET|DELETE\\s+FROM)\\b[^\"'`]*\\$\\{"
                            + "|(?i)\\bf[\"'](SELECT|INSERT|UPDATE|DELETE)\\b[^\"']*\\{")),
            new Rule("command-execution", Severity.HIGH,
                    "Shell command execution",
                    "The code runs an operating-system command. With user input in it, this is command injection.",
                    "Avoid the shell; pass arguments as a list, and validate anything that comes from users.",
                    Pattern.compile("Runtime\\.getRuntime\\(\\)\\.exec\\(|\\bos\\.system\\(|child_process"
                            + "|subprocess\\.\\w+\\(.*shell\\s*=\\s*True")),
            new Rule("dynamic-code", Severity.HIGH,
                    "Code built at runtime",
                    "eval() and new Function() run strings as code; with user input that's remote code execution.",
                    "Parse the data instead (for example JSON.parse) or use a lookup table.",
                    Pattern.compile("\\beval\\s*\\(|\\bnew\\s+Function\\s*\\(")),
            new Rule("tls-verification-disabled", Severity.HIGH,
                    "TLS certificate checks turned off",
                    "Disabling certificate verification lets anyone on the network read and change the traffic.",
                    "Keep verification on; trust a specific certificate if you must talk to a self-signed server.",
                    Pattern.compile("rejectUnauthorized\\s*:\\s*false|verify\\s*=\\s*False"
                            + "|InsecureSkipVerify\\s*:\\s*true|NoopHostnameVerifier|TrustAllCerts")),
            new Rule("raw-html", Severity.MEDIUM,
                    "Raw HTML inserted into the page",
                    "Inserting HTML strings bypasses the framework's escaping; untrusted content becomes XSS.",
                    "Render text instead, or sanitize the HTML with a library such as DOMPurify first.",
                    Pattern.compile("dangerouslySetInnerHTML|\\.innerHTML\\s*=|\\bv-html\\b")),
            new Rule("weak-hash", Severity.MEDIUM,
                    "Weak hash algorithm",
                    "MD5 and SHA-1 are broken for security purposes (collisions are practical).",
                    "Use SHA-256 for integrity, and bcrypt/argon2 for passwords.",
                    Pattern.compile("(?i)MessageDigest\\.getInstance\\(\\s*\"(MD5|SHA-?1)\""
                            + "|hashlib\\.(md5|sha1)\\(|createHash\\(\\s*['\"](md5|sha1)['\"]"))
    );

    private SecurityRules() {
    }

    /** Every rule match on an added line of these files; at most one match per rule and line. */
    public static List<Match> scan(List<DiffFile> files) {
        List<Match> matches = new ArrayList<>();
        for (DiffFile file : files) {
            for (DiffFile.Line line : file.addedLines()) {
                for (Rule rule : RULES) {
                    if (rule.pattern().matcher(line.content()).find()) {
                        matches.add(new Match(rule, file.path(), line.newLine()));
                    }
                }
            }
        }
        return matches;
    }
}
