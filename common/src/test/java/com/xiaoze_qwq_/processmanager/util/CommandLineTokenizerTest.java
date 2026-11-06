package com.xiaoze_qwq_.processmanager.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandLineTokenizerTest {

    @Test
    void tokenizeHandlesSpacesAndQuotes() {
        List<String> tokens = CommandLineTokenizer.tokenize("java -jar \"my server.jar\" --flag 'a b'");
        assertEquals(List.of("java", "-jar", "my server.jar", "--flag", "a b"), tokens);
    }

    @Test
    void tokenizeReturnsEmptyForBlankInput() {
        assertTrue(CommandLineTokenizer.tokenize("").isEmpty());
        assertTrue(CommandLineTokenizer.tokenize("   ").isEmpty());
        assertTrue(CommandLineTokenizer.tokenize(null).isEmpty());
    }

    @Test
    void tokenizeHandlesEscapesInsideDoubleQuotes() {
        List<String> tokens = CommandLineTokenizer.tokenize("cmd \"a\\\"b\" c");
        assertEquals(List.of("cmd", "a\"b", "c"), tokens);
    }

    @Test
    void tokenizeKeepsEmptyQuotedArgument() {
        List<String> tokens = CommandLineTokenizer.tokenize("cmd \"\" next");
        assertEquals(List.of("cmd", "", "next"), tokens);
    }

    @Test
    void tokenizePreservesSingleQuotedSpaces() {
        List<String> tokens = CommandLineTokenizer.tokenize("run '--name=a b' --x");
        assertEquals(List.of("run", "--name=a b", "--x"), tokens);
    }
}
