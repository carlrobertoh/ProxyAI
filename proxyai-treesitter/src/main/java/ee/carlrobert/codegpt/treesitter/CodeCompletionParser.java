package ee.carlrobert.codegpt.treesitter;

import java.nio.charset.StandardCharsets;
import org.treesitter.TSInputEdit;
import org.treesitter.TSLanguage;
import org.treesitter.TSParser;
import org.treesitter.TSPoint;
import org.treesitter.TSTree;

public class CodeCompletionParser {

  private final TSParser parser;

  public CodeCompletionParser(TSLanguage language) {
    parser = new TSParser();
    parser.setLanguage(language);
  }

  public String parse(String prefix, String suffix, String output) {
    var result = new StringBuilder(output);
    String input = prefix + result + suffix;
    TSTree currentTree = parser.parseString(null, input);

    while (!result.isEmpty()) {
      if (containsError(currentTree)) {
        int deletionIndex = prefix.length() + result.length() - 1;
        Position pos = getPosition(input, deletionIndex);

        int startByte = pos.byteOffset;
        int oldEndByte = startByte + getByteLength(result.substring(result.length() - 1));

        TSPoint startPoint = pos.point;
        TSPoint oldEndPoint = computeOldEndPoint(startPoint, result.charAt(result.length() - 1));

        currentTree.edit(
            new TSInputEdit(startByte, oldEndByte, startByte, startPoint, oldEndPoint, startPoint));

        result.deleteCharAt(result.length() - 1);

        input = prefix + result + suffix;

        currentTree = parser.parseString(currentTree, input);
      } else {
        return result.toString();
      }
    }

    return output;
  }

  private boolean containsError(TSTree tree) {
    var treeString = tree.getRootNode().toString();
    return treeString.contains("ERROR")
        || treeString.contains("MISSING \"}\"")
        || treeString.contains("MISSING \")\"");
  }

  private Position getPosition(String input, int index) {
    int row = 0;
    int col = 0;
    int byteOffset = 0;
    for (int i = 0; i < index; i++) {
      char c = input.charAt(i);
      int charByteLength = getByteLength(String.valueOf(c));
      byteOffset += charByteLength;

      if (c == '\n') {
        row++;
        col = 0;
      } else {
        col++;
      }
    }
    return new Position(new TSPoint(row, col), byteOffset);
  }

  private int getByteLength(String str) {
    return str.getBytes(StandardCharsets.UTF_8).length;
  }

  private TSPoint computeOldEndPoint(TSPoint startPoint, char deletedChar) {
    int row = startPoint.getRow();
    int col = startPoint.getColumn();

    if (deletedChar == '\n') {
      row++;
      col = 0;
    } else {
      col++;
    }
    return new TSPoint(row, col);
  }

  private record Position(TSPoint point, int byteOffset) {

  }
}
