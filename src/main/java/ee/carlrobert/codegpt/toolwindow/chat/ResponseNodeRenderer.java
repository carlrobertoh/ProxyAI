package ee.carlrobert.codegpt.toolwindow.chat;

import com.intellij.ui.ColorUtil;
import com.intellij.ui.JBColor;
import com.vladsch.flexmark.ast.BulletListItem;
import com.vladsch.flexmark.ast.Code;
import com.vladsch.flexmark.ast.CodeBlock;
import com.vladsch.flexmark.ast.Heading;
import com.vladsch.flexmark.ast.Link;
import com.vladsch.flexmark.ast.OrderedListItem;
import com.vladsch.flexmark.ast.Paragraph;
import com.vladsch.flexmark.ext.tables.TableBlock;
import com.vladsch.flexmark.ext.tables.TableCell;
import com.vladsch.flexmark.ext.tables.TableHead;
import com.vladsch.flexmark.ext.tables.TableRow;
import com.vladsch.flexmark.html.HtmlWriter;
import com.vladsch.flexmark.html.renderer.NodeRenderer;
import com.vladsch.flexmark.html.renderer.NodeRendererContext;
import com.vladsch.flexmark.html.renderer.NodeRendererFactory;
import com.vladsch.flexmark.html.renderer.NodeRenderingHandler;
import com.vladsch.flexmark.util.data.DataHolder;
import java.util.Set;
import org.jetbrains.annotations.NotNull;

public class ResponseNodeRenderer implements NodeRenderer {

  @Override
  public Set<NodeRenderingHandler<?>> getNodeRenderingHandlers() {
    return Set.of(
        new NodeRenderingHandler<>(Paragraph.class, this::renderParagraph),
        new NodeRenderingHandler<>(Code.class, this::renderCode),
        new NodeRenderingHandler<>(CodeBlock.class, this::renderCodeBlock),
        new NodeRenderingHandler<>(BulletListItem.class, this::renderBulletListItem),
        new NodeRenderingHandler<>(Heading.class, this::renderHeading),
        new NodeRenderingHandler<>(OrderedListItem.class, this::renderOrderedListItem),
        new NodeRenderingHandler<>(TableBlock.class, this::renderTable),
        new NodeRenderingHandler<>(TableRow.class, this::renderTableRow),
        new NodeRenderingHandler<>(TableCell.class, this::renderTableCell)
    );
  }

  private void renderTable(TableBlock node, NodeRendererContext context, HtmlWriter html) {
    var borderColor = ColorUtil.toHex(new JBColor(0xD0D0D0, 0x3C3F47));
    html.attr("style",
        "border-collapse: collapse; width: 100%; margin: 8px 0; border-top: 1px solid "
            + borderColor);
    context.delegateRender();
  }

  private void renderTableRow(TableRow node, NodeRendererContext context, HtmlWriter html) {
    html.attr("style",
        "border-bottom: 1px solid " + ColorUtil.toHex(new JBColor(0xE3E3E3, 0x2D2F35)) + ";");
    context.delegateRender();
  }

  private void renderTableCell(TableCell node, NodeRendererContext context, HtmlWriter html) {
    TableRow row = (TableRow) node.getParent();
    var isHeaderCell = row != null && row.getParent() instanceof TableHead;
    var tag = isHeaderCell ? "th" : "td";

    var styleBuilder = new StringBuilder();
    styleBuilder.append("padding: 8px 12px; text-align: left; vertical-align: middle;");

    if (isHeaderCell) {
      var bgColor = ColorUtil.toHex(new JBColor(0xF2F3F5, 0x3A3D41));
      styleBuilder.append(" font-weight: 600; background-color: ").append(bgColor).append("; color: white; min-width: 200px;");
    }

    html.attr("style", styleBuilder.toString().trim());
    if (isHeaderCell) {
      html.attr("scope", "col");
    }
    html.withAttr().tag(tag);
    context.renderChildren(node);
    html.tag("/" + tag);
  }

  private void renderCodeBlock(CodeBlock node, NodeRendererContext context, HtmlWriter html) {
    html.attr("style", "white-space: pre-wrap;");
    context.delegateRender();
  }

  private void renderHeading(Heading node, NodeRendererContext context, HtmlWriter html) {
    html.attr("style", "margin-top: 8px; margin-bottom: 4px;");
    context.delegateRender();
  }

  private void renderParagraph(Paragraph node, NodeRendererContext context, HtmlWriter html) {
    if (node.getParent() instanceof BulletListItem || node.getParent() instanceof OrderedListItem) {
      html.attr("style", "margin: 0; padding:0;");
    } else {
      html.attr("style", "margin-top: 4px; margin-bottom: 4px;");
    }
    context.delegateRender();
  }

  private void renderCode(Code node, NodeRendererContext context, HtmlWriter html) {
    if (!(node.getParent() instanceof Link)) {
      html.attr("style", "color: " + ColorUtil.toHex(new JBColor(0x00627A, 0xCC7832)));
    }
    context.delegateRender();
  }

  private void renderBulletListItem(
      BulletListItem node,
      NodeRendererContext context,
      HtmlWriter html) {
    html.attr("style", "margin-bottom: 4px;");
    context.delegateRender();
  }

  private void renderOrderedListItem(
      OrderedListItem node,
      NodeRendererContext context,
      HtmlWriter html) {
    html.attr("style", "margin-bottom: 4px;");
    context.delegateRender();
  }

  public static class Factory implements NodeRendererFactory {

    @NotNull
    @Override
    public NodeRenderer apply(@NotNull DataHolder options) {
      return new ResponseNodeRenderer();
    }
  }
}