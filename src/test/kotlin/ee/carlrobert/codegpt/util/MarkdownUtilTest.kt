package ee.carlrobert.codegpt.util

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class MarkdownUtilTest {

  @Test
  fun shouldRenderTableCorrectly() {
    val markdown = """
        <think>thinking content</think>
        Here's a basic fruits table:
        
        | Fruit | Color | Price ($/kg) | Season | Calories (per 100g) |
        |-------|-------|--------------|--------|---------------------|
        | Apple | Red | 3.50 | Fall | 52 |
        | Banana | Yellow | 2.80 | Year-round | 89 |
        | Orange | Orange | 4.20 | Winter | 47 |
        | Strawberry | Red | 6.00 | Spring | 32 |
        | Grape | Purple | 5.50 | Summer | 69 |
        | Mango | Orange | 7.00 | Summer | 60 |
        | Watermelon | Green | 1.90 | Summer | 30 |
        | Kiwi | Brown | 4.80 | Winter | 61 |
        | Pineapple | Yellow | 5.00 | Year-round | 50 |
        | Blueberry | Blue | 8.50 | Summer | 57 |
    """.trimIndent()

    val html = MarkdownUtil.convertMdToHtml(markdown)

    assertThat(html).contains("thead")
    assertThat(html).contains("/thead")
    assertThat(html).contains("tbody")
    assertThat(html).contains("/tbody")
    assertThat(html).contains("th")
    assertThat(html).contains("Fruit")
    assertThat(html).contains("td")
    assertThat(html).contains("Apple")
    val tableCount = html.split("<table").size - 1
    assertThat(tableCount).isEqualTo(1)
  }

  @Test
  fun shouldExtractMarkdownCodeBlocks() {
    val testInput = """
            **C++ Code Block**
            ```cpp
            #include <iostream>

            int main() {
                return 0;
            }
            ```
            1. We include the **iostream** header file.
            2. We define the main function.

            **Java Code Block**
            ```java
            public class Main {
                public static void main(String[] args) {
                }
            }
            ```
            1. We define a **public class** called **Main**.
            2. We define the **main** method which is the entry point of the program.

            """.trimIndent()

    val result = MarkdownUtil.splitCodeBlocks(testInput)

    assertThat(result).containsExactly("""
            **C++ Code Block**

            """.trimIndent(), """
            ```cpp
            #include <iostream>

            int main() {
                return 0;
            }
            ```""".trimIndent(), """

            1. We include the **iostream** header file.
            2. We define the main function.

            **Java Code Block**

            """.trimIndent(), """
            ```java
            public class Main {
                public static void main(String[] args) {
                }
            }
            ```""".trimIndent(), """

            1. We define a **public class** called **Main**.
            2. We define the **main** method which is the entry point of the program.

            """.trimIndent())
  }

  @Test
  fun shouldExtractMarkdownWithoutCode() {
    val testInput = """
            **C++ Code Block**
            1. We include the **iostream** header file.
            2. We define the main function.



            """.trimIndent()

    val result = MarkdownUtil.splitCodeBlocks(testInput)

    assertThat(result).containsExactly("""
            **C++ Code Block**
            1. We include the **iostream** header file.
            2. We define the main function.



            """.trimIndent())
  }

  @Test
  fun shouldExtractMarkdownCodeOnly() {
    val testInput = """
            ```cpp
            #include <iostream>

            int main() {
                return 0;
            }
            ```
            ```java
            public class Main {
                public static void main(String[] args) {
                }
            }
            ```

            """.trimIndent()

    val result = MarkdownUtil.splitCodeBlocks(testInput)

    assertThat(result).containsExactly("""
            ```cpp
            #include <iostream>

            int main() {
                return 0;
            }
            ```""".trimIndent(), """
            ```java
            public class Main {
                public static void main(String[] args) {
                }
            }
            ```""".trimIndent())
  }
}
