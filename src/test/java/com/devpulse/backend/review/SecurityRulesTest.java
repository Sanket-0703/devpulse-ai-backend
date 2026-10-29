package com.devpulse.backend.review;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityRulesTest {

    // Secret-looking samples are assembled at runtime so secret scanners
    // (gitleaks in CI) don't mistake this test for a leak.
    private static final String KEY_HEADER = "-----BEGIN RSA " + "PRIVATE" + " KEY-----";
    private static final String AWS_KEY = "AKIA" + "ABCDEFGHIJKLMNOP";

    @ParameterizedTest(name = "{0}: {1}")
    @CsvSource(delimiter = '|', textBlock = """
            hardcoded-credential      | private static final String DB_PASSWORD = "admin123";
            hardcoded-credential      | apiKey: 'demo1234',
            sql-concatenation         | jdbc.query("SELECT * FROM users WHERE id = '" + id + "'", mapper);
            sql-concatenation         | db.query(`SELECT * FROM users WHERE id = ${id}`)
            sql-concatenation         | cursor.execute(f"DELETE FROM users WHERE id = {user_id}")
            command-execution         | Runtime.getRuntime().exec("rm -rf " + path);
            command-execution         | subprocess.run(cmd, shell=True)
            dynamic-code              | const result = eval(userInput);
            tls-verification-disabled | https.request({ rejectUnauthorized: false })
            tls-verification-disabled | requests.get(url, verify=False)
            raw-html                  | <div dangerouslySetInnerHTML={{ __html: note }} />
            raw-html                  | element.innerHTML = comment;
            weak-hash                 | MessageDigest.getInstance("MD5");
            weak-hash                 | hashlib.sha1(data)
            """)
    void eachRuleCatchesItsPattern(String ruleId, String code) {
        assertThat(ruleIds(code)).contains(ruleId);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "String password = System.getenv(\"DB_PASSWORD\");",
            "spring.datasource.password=${POSTGRES_PASSWORD}",
            "token: '<your token here>'",
            "jdbc.query(\"SELECT * FROM users WHERE id = ?\", mapper, id);",
            "MessageDigest.getInstance(\"SHA-256\");",
            "element.textContent = comment;",
            "evaluate(expression);"
    })
    void safeCodeIsLeftAlone(String code) {
        assertThat(ruleIds(code)).isEmpty();
    }

    @org.junit.jupiter.api.Test
    void keysAreCritical() {
        assertThat(ruleIds(KEY_HEADER)).containsExactly("private-key");
        assertThat(ruleIds("aws_access_key_id = " + AWS_KEY)).contains("aws-access-key");
    }

    @org.junit.jupiter.api.Test
    void onlyAddedLinesAreScannedWithTheirNewLineNumbers() {
        DiffFile file = DiffFile.parse("Dao.java", "modified", """
                @@ -5,2 +5,2 @@
                 eval(old); // context: not part of this change
                -String pwd = "hunter22";
                +String pwd = System.getenv("PWD");
                +exec(eval(input));
                """);

        List<SecurityRules.Match> matches = SecurityRules.scan(List.of(file));

        assertThat(matches).hasSize(1);
        assertThat(matches.getFirst().rule().id()).isEqualTo("dynamic-code");
        assertThat(matches.getFirst().line()).isEqualTo(7);
        assertThat(matches.getFirst().path()).isEqualTo("Dao.java");
    }

    private static List<String> ruleIds(String code) {
        DiffFile file = DiffFile.parse("X", "added", "@@ -0,0 +1 @@\n+" + code);
        return SecurityRules.scan(List.of(file)).stream().map(m -> m.rule().id()).toList();
    }
}
