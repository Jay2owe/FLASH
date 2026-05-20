package flash.pipeline.ui.sandbox;

import flash.pipeline.image.FilterMacroEditorModel;
import flash.pipeline.image.dag.DagToIjmEmitter;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;

public final class NodeEditorPanel extends JPanel {

    private final Runnable changeCallback;
    private SandboxModel.Node node;
    private boolean rebuilding;

    public NodeEditorPanel(Runnable changeCallback) {
        super(new GridBagLayout());
        this.changeCallback = changeCallback;
        setBorder(BorderFactory.createTitledBorder("Step settings"));
        showEmpty("Click a step in 'Your filter' to edit its settings.");
    }

    public void setNode(SandboxModel.Node node) {
        this.node = node;
        rebuild();
    }

    private void rebuild() {
        rebuilding = true;
        removeAll();
        if (node == null) {
            showEmpty("Click a step in 'Your filter' to edit its settings.");
            finishRebuild();
            return;
        }
        String command = node.commandName.length() > 0 ? node.commandName : DagToIjmEmitter.commandFor(node.type);
        addLabel(command == null ? node.type.name() : command, 0, 0, 2, true);

        List<Token> tokens = parseArgs(node.args);
        int row = 1;
        int editableCount = 0;
        for (int i = 0; i < tokens.size(); i++) {
            final Token token = tokens.get(i);
            if (!token.editable) continue;
            editableCount++;
            addLabel(token.key, 0, row, 1, false);
            String value = token.parameter.getValue();
            final JTextField field = new JTextField(value, Math.max(6, Math.min(14, value.length() + 2)));
            addField(field, 1, row);
            final List<Token> allTokens = tokens;
            field.getDocument().addDocumentListener(new DocumentListener() {
                @Override public void insertUpdate(DocumentEvent e) { update(); }
                @Override public void removeUpdate(DocumentEvent e) { update(); }
                @Override public void changedUpdate(DocumentEvent e) { update(); }
                private void update() {
                    if (rebuilding || NodeEditorPanel.this.node == null) return;
                    token.parameter.setValue(field.getText().trim());
                    NodeEditorPanel.this.node.args = renderArgs(allTokens);
                    if (changeCallback != null) changeCallback.run();
                }
            });
            row++;
        }
        if (editableCount == 0) {
            addLabel("This operation has no key=value parameters.", 0, row, 2, false);
        }
        finishRebuild();
    }

    private void finishRebuild() {
        rebuilding = false;
        revalidate();
        repaint();
    }

    private void showEmpty(String message) {
        addLabel(message, 0, 0, 2, false);
    }

    private void addLabel(String text, int x, int y, int width, boolean header) {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = x;
        gbc.gridy = y;
        gbc.gridwidth = width;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = new Insets(y == 0 ? 4 : 2, 4, 2, 4);
        JLabel label = new JLabel(text);
        if (header) label.setFont(label.getFont().deriveFont(java.awt.Font.BOLD));
        add(label, gbc);
    }

    private void addField(JTextField field, int x, int y) {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = x;
        gbc.gridy = y;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(2, 4, 2, 4);
        add(field, gbc);
    }

    private static List<Token> parseArgs(String args) {
        List<Token> tokens = new ArrayList<Token>();
        if (args == null || args.trim().isEmpty()) return tokens;
        List<String> parts = RecorderParameterProbe.tokenizeOptions(args);
        for (int i = 0; i < parts.size(); i++) {
            String part = parts.get(i);
            int equals = part.indexOf('=');
            if (equals > 0 && equals < part.length() - 1) {
                String key = part.substring(0, equals);
                String value = part.substring(equals + 1);
                tokens.add(new Token(key,
                        new FilterMacroEditorModel.Parameter(key, value, value, "", "")));
            } else {
                tokens.add(new Token(part));
            }
        }
        return tokens;
    }

    private static String renderArgs(List<Token> tokens) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tokens.size(); i++) {
            if (i > 0) sb.append(' ');
            Token token = tokens.get(i);
            if (token.editable) {
                sb.append(token.key).append('=').append(token.parameter.getValue());
            } else {
                sb.append(token.key);
            }
        }
        return sb.toString();
    }

    private static final class Token {
        final String key;
        final boolean editable;
        final FilterMacroEditorModel.Parameter parameter;

        Token(String key) {
            this.key = key == null ? "" : key;
            this.editable = false;
            this.parameter = null;
        }

        Token(String key, FilterMacroEditorModel.Parameter parameter) {
            this.key = key == null ? "" : key;
            this.editable = true;
            this.parameter = parameter;
        }
    }
}
