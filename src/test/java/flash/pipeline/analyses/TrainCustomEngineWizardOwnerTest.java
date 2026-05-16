package flash.pipeline.analyses;

import flash.pipeline.ui.config.ConfigQcActions;
import flash.pipeline.ui.config.ConfigQcContext;
import flash.pipeline.ui.config.ConfigQcDialog;
import flash.pipeline.ui.config.ConfigQcStage;
import flash.pipeline.ui.config.SegmentationMethodStage;
import flash.pipeline.ui.wizard.TrainCustomEngineWorkflow;
import ij.ImagePlus;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;

public class TrainCustomEngineWizardOwnerTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void trainCustomEngineLauncherPassesQcDialogOwnerToWizard() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        File projectRoot = temp.newFolder("project");
        File binFolder = new File(projectRoot, ".bin");
        assertTrue(binFolder.mkdirs());
        CreateBinFileAnalysis.BinUserConfig cfg = oneChannelConfig();
        ConfigQcContext context = ConfigQcContext.fromImages(
                projectRoot,
                binFolder,
                cfg,
                Collections.<ImagePlus>emptyList(),
                Collections.singletonList("IBA1"),
                0);
        RecordingAnalysis analysis = new RecordingAnalysis();
        SegmentationMethodStage.TrainCustomEngineLauncher launcher =
                invokeCreateTrainCustomEngineLauncher(analysis, cfg, binFolder, 0);
        Frame parent = new Frame("QC parent");
        Window qcOwner = null;
        try {
            ConfigQcDialog.createModal(parent, context,
                    Collections.<ConfigQcStage>singletonList(new NoopStage()));
            qcOwner = context.getWindowOwner();
            assertNotNull(qcOwner);

            assertTrue(launcher.launch(context, new TokenStore()));

            assertSame(qcOwner, analysis.capturedOwner);
            assertNotNull(analysis.capturedWorkflow);
        } finally {
            if (qcOwner != null) {
                qcOwner.dispose();
            }
            parent.dispose();
        }
    }

    private static CreateBinFileAnalysis.BinUserConfig oneChannelConfig() {
        return new CreateBinFileAnalysis.BinUserConfig(
                new ArrayList<String>(Collections.singletonList("IBA1")),
                new ArrayList<String>(Collections.singletonList("Green")),
                new ArrayList<String>(Collections.singletonList("default")),
                new ArrayList<String>(Collections.singletonList("100-Infinity")),
                new ArrayList<String>(Collections.singletonList("None")),
                new ArrayList<String>(Collections.singletonList("Default")),
                new ArrayList<String>(Collections.singletonList("default")));
    }

    private static SegmentationMethodStage.TrainCustomEngineLauncher invokeCreateTrainCustomEngineLauncher(
            CreateBinFileAnalysis analysis,
            CreateBinFileAnalysis.BinUserConfig cfg,
            File binFolder,
            int channelIndex) throws Exception {
        Method method = CreateBinFileAnalysis.class.getDeclaredMethod(
                "createTrainCustomEngineLauncher",
                CreateBinFileAnalysis.BinUserConfig.class,
                File.class,
                int.class);
        method.setAccessible(true);
        return (SegmentationMethodStage.TrainCustomEngineLauncher) method.invoke(
                analysis, cfg, binFolder, Integer.valueOf(channelIndex));
    }

    private static final class RecordingAnalysis extends CreateBinFileAnalysis {
        Window capturedOwner;
        TrainCustomEngineWorkflow capturedWorkflow;

        @Override protected boolean showTrainCustomEngineWizard(Window owner,
                                                                TrainCustomEngineWorkflow workflow) {
            capturedOwner = owner;
            capturedWorkflow = workflow;
            return true;
        }
    }

    private static final class TokenStore implements SegmentationMethodStage.MethodStore {
        private String token = "classical";

        @Override public String getChoice() {
            return SegmentationMethodStage.choiceForMethodToken(token);
        }

        @Override public boolean selectChoice(String choice) {
            token = SegmentationMethodStage.CLASSICAL.equals(choice) ? "classical" : choice;
            return true;
        }

        @Override public String getMethodToken() {
            return token;
        }

        @Override public void setMethodToken(String methodToken) {
            token = methodToken;
        }
    }

    private static final class NoopStage implements ConfigQcStage {
        @Override public String title() {
            return "Noop";
        }

        @Override public JComponent buildControls(ConfigQcContext context, ConfigQcActions actions) {
            return new JPanel();
        }

        @Override public boolean lockIn(ConfigQcContext context) {
            return true;
        }
    }
}
