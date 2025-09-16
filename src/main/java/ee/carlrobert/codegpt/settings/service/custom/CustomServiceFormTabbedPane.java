package ee.carlrobert.codegpt.settings.service.custom;

import static java.util.stream.Collectors.toMap;

import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import javax.swing.JPanel;
import javax.swing.table.DefaultTableModel;

public class CustomServiceFormTabbedPane extends JBTabbedPane {

  private final JBTable headersTable;
  private final JBTable bodyTable;

  public CustomServiceFormTabbedPane(Map<String, String> headers, Map<String, ?> body) {
    headersTable = new JBTable(
        new DefaultTableModel(toArray(headers),
            new Object[]{"Key", "Value"}));

    bodyTable = new JBTable(
        new DefaultTableModel(toArray(body),
            new Object[]{"Key", "Value"}));
    bodyTable.setVisibleRowCount(6);

    setTabComponentInsets(JBUI.insetsTop(8));
    addTab("Headers", createTablePanel(headersTable));
    addTab("Body", createTablePanel(bodyTable));
  }

  public void setEnabled(boolean enabled) {
    headersTable.setEnabled(enabled);
    bodyTable.setEnabled(enabled);
  }

  public void setHeaders(Map<String, String> headers) {
    setTableData(headersTable, headers);
  }

  public Map<String, String> getHeaders() {
    return getTableData(headersTable).entrySet().stream()
        .filter(entry -> entry.getKey() != null && entry.getValue() != null)
        .collect(toMap(Entry::getKey, entry -> String.valueOf(entry.getValue())));
  }

  public void setBody(Map<String, Object> body) {
    setTableData(bodyTable, body);
  }

  public Map<String, Object> getBody() {
    return getTableData(bodyTable);
  }

  private void setTableData(JBTable table, Map<String, ?> values) {
    DefaultTableModel model = (DefaultTableModel) table.getModel();

    if (hasTableDataChanged(model, values)) {
      model.setRowCount(0);
      for (var entry : values.entrySet()) {
        model.addRow(new Object[]{entry.getKey(), entry.getValue()});
      }
    }
  }

  private boolean hasTableDataChanged(DefaultTableModel model, Map<String, ?> newValues) {
    if (model.getRowCount() != newValues.size()) {
      return true;
    }

    for (int i = 0; i < model.getRowCount(); i++) {
      String key = (String) model.getValueAt(i, 0);
      Object value = model.getValueAt(i, 1);

      if (!newValues.containsKey(key) || !java.util.Objects.equals(newValues.get(key), value)) {
        return true;
      }
    }

    return false;
  }

  private Map<String, Object> getTableData(JBTable table) {
    var model = (DefaultTableModel) table.getModel();
    var data = new HashMap<String, Object>();
    for (int i = 0; i < model.getRowCount(); i++) {
      var key = (String) model.getValueAt(i, 0);
      data.put(key, parseValue(model.getValueAt(i, 1)));
    }
    return data;
  }

  private static Object parseValue(Object value) {
    if (!(value instanceof String stringValue)) {
      return value;
    }

    try {
      return Integer.parseInt(stringValue);
    } catch (NumberFormatException e) {
      // ignore
    }
    try {
      return Double.parseDouble(stringValue);
    } catch (NumberFormatException e) {
      // ignore
    }
    if (List.of("true", "false").contains(stringValue.toLowerCase().trim())) {
      return Boolean.parseBoolean(stringValue);
    }
    return value;
  }

  public static Object[][] toArray(Map<?, ?> actionsMap) {
    return actionsMap.entrySet()
        .stream()
        .map(entry -> new Object[]{entry.getKey(), entry.getValue()})
        .toArray(Object[][]::new);
  }

  private JPanel createTablePanel(JBTable table) {
    return ToolbarDecorator.createDecorator(table)
        .setAddAction(anActionButton ->
            ((DefaultTableModel) table.getModel()).addRow(new Object[]{"", null}))
        .setRemoveAction(anActionButton ->
            ((DefaultTableModel) table.getModel()).removeRow(table.getSelectedRow()))
        .disableUpAction()
        .disableDownAction()
        .createPanel();
  }
}
