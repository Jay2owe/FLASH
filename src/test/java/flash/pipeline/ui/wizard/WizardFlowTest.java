package flash.pipeline.ui.wizard;

import flash.pipeline.ui.PipelineDialog;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class WizardFlowTest {

    @Test
    public void skipsConditionalScreensAndWritesInRegistrationOrder() {
        RecordingBinding binding = new RecordingBinding();
        ScriptedWizard wizard = new ScriptedWizard(binding,
                Arrays.asList(WizardFlow.ScreenResult.NEXT, WizardFlow.ScreenResult.NEXT));
        wizard.includeMiddle = false;

        WizardFlow.AnswerMap answers = wizard.run();

        assertFalse(answers.getBoolean("includeMiddle", true));
        assertTrue(wizard.wasFinished());
        assertFalse(wizard.wasCancelled());
        assertEquals(Arrays.asList(0, 2), wizard.visited);
        assertEquals(Arrays.asList("first", "final"), binding.setOrder);
        assertEquals(Arrays.asList("first", "final"), binding.recommendedOrder);
    }

    @Test
    public void backAndForwardKeepEarlierAnswers() {
        RecordingBinding binding = new RecordingBinding();
        ScriptedWizard wizard = new ScriptedWizard(binding,
                Arrays.asList(WizardFlow.ScreenResult.NEXT,
                        WizardFlow.ScreenResult.NEXT,
                        WizardFlow.ScreenResult.BACK,
                        WizardFlow.ScreenResult.NEXT,
                        WizardFlow.ScreenResult.NEXT));
        wizard.includeMiddle = true;

        WizardFlow.AnswerMap answers = wizard.run();

        assertEquals("alpha", answers.getString("first", ""));
        assertEquals("middle", answers.getString("middle", ""));
        assertEquals("done", answers.getString("final", ""));
        assertEquals(Arrays.asList(0, 1, 2, 1, 2), wizard.visited);
    }

    @Test
    public void cancelDoesNotFinishOrWriteBackPartialAnswers() {
        RecordingBinding binding = new RecordingBinding();
        ScriptedWizard wizard = new ScriptedWizard(binding,
                Arrays.asList(WizardFlow.ScreenResult.NEXT, WizardFlow.ScreenResult.CANCEL));

        WizardFlow.AnswerMap answers = wizard.run();

        assertEquals("alpha", answers.getString("first", ""));
        assertTrue(wizard.wasCancelled());
        assertFalse(wizard.wasFinished());
        assertEquals(0, binding.setOrder.size());
        assertEquals(0, binding.recommendedOrder.size());
    }

    @Test
    public void headlessRunCountsAsFinishedWithoutWriteback() {
        RecordingBinding binding = new RecordingBinding();
        ScriptedWizard wizard = new ScriptedWizard(binding, Arrays.<WizardFlow.ScreenResult>asList(), true);

        wizard.run();

        assertTrue(wizard.wasFinished());
        assertFalse(wizard.wasCancelled());
        assertEquals(0, binding.setOrder.size());
    }

    @Test
    public void headlessRunReturnsDefaultsWithoutWriteback() {
        RecordingBinding binding = new RecordingBinding();
        ScriptedWizard wizard = new ScriptedWizard(binding, Arrays.<WizardFlow.ScreenResult>asList(), true);

        WizardFlow.AnswerMap answers = wizard.run();

        assertEquals("default-first", answers.getString("first", ""));
        assertEquals(0, binding.setOrder.size());
    }

    private static final class ScriptedWizard extends WizardFlow {
        private final List<WizardFlow.ScreenResult> script;
        private final List<Integer> visited = new ArrayList<Integer>();
        private int call;
        boolean includeMiddle = true;

        ScriptedWizard(WizardFlow.MainPanelBinding binding, List<WizardFlow.ScreenResult> script) {
            this(binding, script, false);
        }

        ScriptedWizard(WizardFlow.MainPanelBinding binding, List<WizardFlow.ScreenResult> script, boolean headless) {
            super("Dummy", binding, headless);
            this.script = script;
            register(new SimpleScreen(0, "first"));
            register(new SimpleScreen(1, "middle") {
                @Override
                public boolean isApplicable(WizardFlow.AnswerMap prior) {
                    return prior.getBoolean("includeMiddle", true);
                }
            });
            register(new SimpleScreen(2, "final"));
        }

        @Override
        protected ScreenResult showScreen(Screen screen, int screenIndex, boolean finish) {
            visited.add(Integer.valueOf(screenIndex));
            ScreenResult result = script.get(call++);
            if (result == ScreenResult.NEXT) {
                if (screenIndex == 0) {
                    setAnswer("first", "alpha");
                    setAnswer("includeMiddle", Boolean.valueOf(includeMiddle));
                } else if (screenIndex == 1) {
                    setAnswer("middle", "middle");
                } else if (screenIndex == 2) {
                    setAnswer("final", "done");
                }
            }
            return result;
        }

        private void setAnswer(String key, Object value) {
            try {
                java.lang.reflect.Field answers = WizardFlow.class.getDeclaredField("answers");
                answers.setAccessible(true);
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) answers.get(this);
                map.put(key, value);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }
    }

    private static class SimpleScreen extends WizardFlow.Screen {
        private final String fieldId;

        SimpleScreen(int index, String fieldId) {
            super("Screen " + index);
            this.fieldId = fieldId;
            defaultAnswer(fieldId, "default-" + fieldId);
        }

        @Override public void build(PipelineDialog dialog, WizardFlow.AnswerMap answers) {}
        @Override public void read(PipelineDialog dialog, WizardFlow.AnswerMap answers) {}

        @Override
        public void writeTo(WizardFlow.MainPanelBinding panel, WizardFlow.AnswerMap answers) {
            panel.setValue(fieldId, answers.get(fieldId));
        }
    }

    private static final class RecordingBinding implements WizardFlow.MainPanelBinding {
        final Map<String, Object> values = new LinkedHashMap<String, Object>();
        final List<String> setOrder = new ArrayList<String>();
        final List<String> recommendedOrder = new ArrayList<String>();

        @Override
        public void setValue(String fieldId, Object value) {
            values.put(fieldId, value);
            setOrder.add(fieldId);
        }

        @Override
        public Object getValue(String fieldId) {
            return values.get(fieldId);
        }

        @Override
        public void markRecommended(String fieldId) {
            recommendedOrder.add(fieldId);
        }
    }
}
