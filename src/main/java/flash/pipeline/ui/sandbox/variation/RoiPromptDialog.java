package flash.pipeline.ui.sandbox.variation;

import ij.ImagePlus;
import ij.gui.Roi;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

public final class RoiPromptDialog {

    private RoiPromptDialog() {}

    public static Roi prompt(final ImagePlus source, final String reasonMessage) {
        if (source == null) throw new IllegalArgumentException("source must not be null");
        if (SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException(
                    "RoiPromptDialog.prompt must not be called from the EDT");
        }

        final Roi previousRoi = source.getRoi();
        source.deleteRoi();

        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<Roi> result = new AtomicReference<Roi>();
        final JDialog[] dialogHolder = new JDialog[1];

        SwingUtilities.invokeLater(new Runnable() {
            @Override public void run() {
                JDialog dialog = buildDialog(source, reasonMessage, result, done);
                dialogHolder[0] = dialog;
                dialog.setVisible(true);
            }
        });

        try {
            done.await();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            result.set(null);
        }

        try {
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override public void run() {
                    JDialog dialog = dialogHolder[0];
                    if (dialog != null) dialog.dispose();
                    if (previousRoi != null) source.setRoi(previousRoi);
                    else source.deleteRoi();
                }
            });
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (InvocationTargetException ite) {
            ite.printStackTrace();
        }

        return result.get();
    }

    private static JDialog buildDialog(final ImagePlus source,
                                       String reasonMessage,
                                       final AtomicReference<Roi> result,
                                       final CountDownLatch done) {
        Window owner = null;
        JDialog dialog = new JDialog(owner, "Draw ROI for variants",
                JDialog.ModalityType.MODELESS);
        dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);

        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));

        String reasonHtml = "<html><div style='width:360px'>"
                + "<b>Memory budget exceeded.</b><br>"
                + escapeHtml(reasonMessage == null ? "" : reasonMessage)
                + "<br><br>Draw a rectangular ROI on <i>"
                + escapeHtml(source.getTitle() == null ? "" : source.getTitle())
                + "</i>, then click Confirm.</div></html>";
        panel.add(new JLabel(reasonHtml), BorderLayout.CENTER);

        JButton confirm = new JButton("Confirm");
        JButton cancel = new JButton("Cancel");

        confirm.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                Roi drawn = source.getRoi();
                if (drawn == null) {
                    Toolkit.getDefaultToolkit().beep();
                    return;
                }
                result.set(drawn);
                done.countDown();
            }
        });
        cancel.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                result.set(null);
                done.countDown();
            }
        });

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.add(cancel);
        buttons.add(confirm);
        panel.add(buttons, BorderLayout.SOUTH);

        dialog.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                result.set(null);
                done.countDown();
            }
        });

        dialog.getContentPane().add(panel);
        dialog.getRootPane().setDefaultButton(confirm);
        dialog.pack();
        dialog.setLocationRelativeTo(null);
        dialog.setAlwaysOnTop(true);
        return dialog;
    }

    private static String escapeHtml(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&':
                    sb.append("&amp;");
                    break;
                case '<':
                    sb.append("&lt;");
                    break;
                case '>':
                    sb.append("&gt;");
                    break;
                default:
                    sb.append(c);
                    break;
            }
        }
        return sb.toString();
    }
}
