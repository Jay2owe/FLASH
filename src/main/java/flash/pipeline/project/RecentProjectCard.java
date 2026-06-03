package flash.pipeline.project;

import flash.pipeline.FLASH_Pipeline;
import flash.pipeline.intelligence.AnalysisStatus;
import flash.pipeline.ui.FlashTheme;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.border.CompoundBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.Map;

/**
 * One recent-project row in the FLASH home screen.
 */
public final class RecentProjectCard extends JPanel {
    private static final int[] STATUS_ORDER = {
            FLASH_Pipeline.IDX_CREATE_BIN,
            FLASH_Pipeline.IDX_DRAW_ROIS,
            FLASH_Pipeline.IDX_DECONVOLUTION,
            FLASH_Pipeline.IDX_SPECTRAL_DECONTAMINATION,
            FLASH_Pipeline.IDX_SPLIT_MERGE,
            FLASH_Pipeline.IDX_INTENSITY,
            FLASH_Pipeline.IDX_3D_OBJECT,
            FLASH_Pipeline.IDX_SPATIAL,
            FLASH_Pipeline.IDX_AGGREGATION,
            FLASH_Pipeline.IDX_STATISTICS,
            FLASH_Pipeline.IDX_EXCEL_EXPORT
    };

    public interface Actions {
        void open(RecentProjectCard card);
        void edit(RecentProjectCard card);
        void locate(RecentProjectCard card);
        void remove(RecentProjectCard card);
    }

    private final RecentProject recent;
    private final Actions actions;
    private final JLabel nameLabel;
    private final JLabel progressLabel;
    private final JButton locateButton;
    private final Color normalBackground;
    private File resolvedProjectJson;
    private ProjectService.ResolveOutcome resolveOutcome;
    private boolean unresolved;

    public RecentProjectCard(RecentProject recent, boolean continueCard, long nowMillis,
                             Actions actions) {
        this.recent = recent == null ? new RecentProject("", "", 0L) : recent;
        this.actions = actions;
        this.normalBackground = continueCard ? FlashTheme.PRIMARY_BG : FlashTheme.SURFACE_RAISED;

        setLayout(new BorderLayout(8, 0));
        setFocusable(true);
        setOpaque(true);
        setBackground(normalBackground);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setBorder(new CompoundBorder(
                BorderFactory.createLineBorder(continueCard
                        ? FlashTheme.PRIMARY_BORDER : FlashTheme.BORDER),
                FlashTheme.pad(8, 10)));

        JPanel text = new JPanel();
        text.setOpaque(false);
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));

        nameLabel = new JLabel(displayName(continueCard));
        nameLabel.setForeground(FlashTheme.TEXT_HEADER);
        nameLabel.setFont(continueCard ? FlashTheme.bodyMedium().deriveFont(Font.BOLD)
                : FlashTheme.bodyMedium());
        text.add(nameLabel);
        text.add(Box.createVerticalStrut(3));

        progressLabel = new JLabel("checking...");
        progressLabel.setForeground(FlashTheme.TEXT_MUTED);
        progressLabel.setFont(FlashTheme.caption());
        text.add(progressLabel);

        JPanel meta = new JPanel();
        meta.setOpaque(false);
        meta.setLayout(new BoxLayout(meta, BoxLayout.Y_AXIS));

        JLabel lastOpened = new JLabel(relativeLastOpened(this.recent.lastOpenedAt, nowMillis));
        lastOpened.setForeground(FlashTheme.TEXT_MUTED);
        lastOpened.setFont(FlashTheme.caption());
        lastOpened.setAlignmentX(RIGHT_ALIGNMENT);

        locateButton = new JButton("Locate...");
        locateButton.setFont(FlashTheme.caption());
        locateButton.setVisible(false);
        locateButton.setAlignmentX(RIGHT_ALIGNMENT);
        locateButton.addActionListener(new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                if (actions != null) {
                    actions.locate(RecentProjectCard.this);
                }
            }
        });
        meta.add(lastOpened);
        meta.add(Box.createVerticalStrut(4));
        meta.add(locateButton);

        add(text, BorderLayout.CENTER);
        add(meta, BorderLayout.EAST);

        Dimension preferred = getPreferredSize();
        setMaximumSize(new Dimension(Integer.MAX_VALUE, Math.max(72, preferred.height)));
        attachMouseActions();
        attachKeyboardActions();
    }

    public RecentProject recent() {
        return recent;
    }

    public File resolvedProjectJson() {
        return resolvedProjectJson;
    }

    public ProjectService.ResolveOutcome resolveOutcome() {
        return resolveOutcome;
    }

    public boolean isUnresolved() {
        return unresolved;
    }

    public void setChecking() {
        unresolved = false;
        resolvedProjectJson = null;
        resolveOutcome = null;
        nameLabel.setForeground(FlashTheme.TEXT_HEADER);
        progressLabel.setText("checking...");
        locateButton.setVisible(false);
        revalidate();
        repaint();
    }

    public void setReconnecting() {
        unresolved = false;
        nameLabel.setForeground(FlashTheme.TEXT_HEADER);
        progressLabel.setText("reconnecting...");
        locateButton.setVisible(false);
        revalidate();
        repaint();
    }

    public void applyStatusResult(StatusResult result) {
        if (result == null) {
            unresolved = false;
            resolvedProjectJson = null;
            resolveOutcome = null;
            nameLabel.setForeground(FlashTheme.TEXT_HEADER);
            progressLabel.setText("status unavailable");
            locateButton.setVisible(false);
            return;
        }
        unresolved = !result.resolved;
        resolvedProjectJson = result.projectJson;
        resolveOutcome = result.outcome;
        nameLabel.setForeground(unresolved ? FlashTheme.TEXT_MUTED : FlashTheme.TEXT_HEADER);
        progressLabel.setText(result.progressText);
        locateButton.setVisible(unresolved);
        revalidate();
        repaint();
    }

    void markResolvedForTests(File projectJson) {
        resolvedProjectJson = projectJson;
        resolveOutcome = new ProjectService.ResolveOutcome(projectJson,
                projectJson == null ? null : projectJson.getAbsolutePath(), false);
        unresolved = false;
        nameLabel.setForeground(FlashTheme.TEXT_HEADER);
        locateButton.setVisible(false);
    }

    String progressTextForTests() {
        return progressLabel.getText();
    }

    boolean locateButtonVisibleForTests() {
        return locateButton.isVisible();
    }

    void clickLocateForTests() {
        locateButton.doClick();
    }

    private String displayName(boolean continueCard) {
        String name = recent.name == null || recent.name.trim().isEmpty()
                ? "(unnamed project)" : recent.name.trim();
        return continueCard ? "> Continue  " + name : name;
    }

    private void attachMouseActions() {
        addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                maybeShowPopup(e);
            }

            @Override public void mouseReleased(MouseEvent e) {
                maybeShowPopup(e);
            }

            @Override public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 2
                        && actions != null) {
                    actions.open(RecentProjectCard.this);
                }
            }

            @Override public void mouseEntered(MouseEvent e) {
                setBackground(FlashTheme.TILE_HOVER_BG);
            }

            @Override public void mouseExited(MouseEvent e) {
                setBackground(normalBackground);
            }
        });
    }

    private void attachKeyboardActions() {
        getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0),
                "openRecent");
        getActionMap().put("openRecent", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                if (actions != null) {
                    actions.open(RecentProjectCard.this);
                }
            }
        });
    }

    private void maybeShowPopup(MouseEvent e) {
        if (!e.isPopupTrigger()) {
            return;
        }
        JPopupMenu menu = new JPopupMenu();
        JMenuItem edit = new JMenuItem("Edit project");
        edit.addActionListener(new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                if (actions != null) {
                    actions.edit(RecentProjectCard.this);
                }
            }
        });
        menu.add(edit);

        if (unresolved) {
            JMenuItem locate = new JMenuItem("Locate...");
            locate.addActionListener(new AbstractAction() {
                @Override public void actionPerformed(ActionEvent e) {
                    if (actions != null) {
                        actions.locate(RecentProjectCard.this);
                    }
                }
            });
            menu.add(locate);
        }

        JMenuItem remove = new JMenuItem("Remove from list");
        remove.addActionListener(new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                if (actions != null) {
                    actions.remove(RecentProjectCard.this);
                }
            }
        });
        menu.add(remove);
        menu.show(e.getComponent(), e.getX(), e.getY());
    }

    static String relativeLastOpened(long lastOpenedAt, long nowMillis) {
        if (lastOpenedAt <= 0L) {
            return "unknown";
        }
        long diff = Math.max(0L, nowMillis - lastOpenedAt);
        long minute = 60L * 1000L;
        long hour = 60L * minute;
        long day = 24L * hour;
        long week = 7L * day;
        long month = 30L * day;
        if (diff < minute) {
            return "now";
        }
        if (diff < hour) {
            return Math.max(1L, diff / minute) + "m ago";
        }
        if (diff < day) {
            return Math.max(1L, diff / hour) + "h ago";
        }
        if (diff < week) {
            return Math.max(1L, diff / day) + "d ago";
        }
        if (diff < month) {
            return Math.max(1L, diff / week) + "w ago";
        }
        return Math.max(1L, diff / month) + "mo ago";
    }

    static String progressSummary(Map<Integer, AnalysisStatus> statuses) {
        int lastDone = -1;
        int firstNotDone = -1;
        for (int i = 0; i < STATUS_ORDER.length; i++) {
            int analysis = STATUS_ORDER[i];
            AnalysisStatus status = statuses == null ? AnalysisStatus.NOT_STARTED
                    : statuses.get(Integer.valueOf(analysis));
            if (status == AnalysisStatus.DONE) {
                lastDone = analysis;
            } else if (firstNotDone < 0) {
                firstNotDone = analysis;
            }
        }

        if (lastDone < 0) {
            return "not started - next: " + shortLabel(firstNotDone);
        }
        if (firstNotDone < 0) {
            return "finished " + shortLabel(lastDone) + " - complete";
        }
        return "finished " + shortLabel(lastDone) + " - next: " + shortLabel(firstNotDone);
    }

    private static String shortLabel(int analysisIndex) {
        if (analysisIndex == FLASH_Pipeline.IDX_CREATE_BIN) return "Configuration";
        if (analysisIndex == FLASH_Pipeline.IDX_DRAW_ROIS) return "ROIs";
        if (analysisIndex == FLASH_Pipeline.IDX_DECONVOLUTION) return "Deconvolution";
        if (analysisIndex == FLASH_Pipeline.IDX_SPLIT_MERGE) return "Presentation Images";
        if (analysisIndex == FLASH_Pipeline.IDX_3D_OBJECT) return "3D Objects";
        if (analysisIndex == FLASH_Pipeline.IDX_SPATIAL) return "Spatial";
        if (analysisIndex == FLASH_Pipeline.IDX_LINE_DISTANCE) return "Line Distance";
        if (analysisIndex == FLASH_Pipeline.IDX_INTENSITY) return "Intensity";
        if (analysisIndex == FLASH_Pipeline.IDX_AGGREGATION) return "Aggregation";
        if (analysisIndex == FLASH_Pipeline.IDX_STATISTICS) return "Statistics";
        if (analysisIndex == FLASH_Pipeline.IDX_EXCEL_EXPORT) return "Excel Export";
        if (analysisIndex == FLASH_Pipeline.IDX_SPECTRAL_DECONTAMINATION) {
            return "Spectral Decontamination";
        }
        return "Analysis";
    }

    static final class StatusResult {
        final boolean resolved;
        final File projectJson;
        final ProjectService.ResolveOutcome outcome;
        final String progressText;

        private StatusResult(boolean resolved, File projectJson,
                             ProjectService.ResolveOutcome outcome,
                             String progressText) {
            this.resolved = resolved;
            this.projectJson = projectJson;
            this.outcome = outcome;
            this.progressText = progressText == null ? "status unavailable" : progressText;
        }

        static StatusResult resolved(File projectJson, String progressText) {
            ProjectService.ResolveOutcome outcome = new ProjectService.ResolveOutcome(projectJson,
                    projectJson == null ? null : projectJson.getAbsolutePath(), false);
            return resolved(outcome, progressText);
        }

        static StatusResult resolved(ProjectService.ResolveOutcome outcome, String progressText) {
            File projectJson = outcome == null ? null : outcome.projectJson;
            return new StatusResult(true, projectJson, outcome, progressText);
        }

        static StatusResult unresolved() {
            return unresolved("Unavailable");
        }

        static StatusResult unresolved(String progressText) {
            return new StatusResult(false, null, null, progressText);
        }
    }
}
