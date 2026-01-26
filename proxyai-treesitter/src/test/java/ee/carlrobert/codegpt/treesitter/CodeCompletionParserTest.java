package ee.carlrobert.codegpt.treesitter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

public class CodeCompletionParserTest {

  @Test
  public void shouldGetValidReturnValue() {
    var prefix = """
        class Main {
          public int getRandomNumber() {
            return\s""";
    var suffix = """

          }
        }""";
    var output = """
        10;}
        }
        public int getRandomNumber(int k) {""";

    var parsedResponse = CodeCompletionParserFactory
        .getParserForFileExtension("java")
        .parse(prefix, suffix, output);

    assertThat(parsedResponse).isEqualTo("10;");
  }

  @Test
  public void shouldGetValidParenthesisValue() {
    var prefix = """
        class Main {
          public int getRandomNumber(int\s""";
    var suffix = """
        ) {
            return 10;
          }
        }""";
    var output = "prevNumber);";

    var parsedResponse = CodeCompletionParserFactory
        .getParserForFileExtension("java")
        .parse(prefix, suffix, output);

    assertThat(parsedResponse).isEqualTo("prevNumber");
  }

  @Test
  public void shouldHandleFormalParameters() {
    var prefix = """
        class Main {
          public int getRandomNumber(""";
    var suffix = """
        ) {
            return 10;
          }
        }""";
    var output = "int prevNumber) }";

    var result = CodeCompletionParserFactory
        .getParserForFileExtension("java")
        .parse(prefix, suffix, output);

    assertThat(result).isEqualTo("int prevNumber");
  }
}
