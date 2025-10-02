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
        new NodeRenderingHandler<>(OrderedListItem.class, this::renderOrderedListItem)
    );
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