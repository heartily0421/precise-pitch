package net.zllr.precisepitch;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class PracticeActivity extends Activity {
    private static final int kMajorScaleSequence[] = { 2, 2, 1, 2, 2, 2, 1 };
    private static final int kCentThreshold = 20;

    private static final int futureNoteColor = Color.rgb(200, 200, 200);
    private static final int successNoteColor = Color.rgb(0, 180, 0);
    private static final int playNoteColor  = Color.BLACK;

    private final ArrayList<StaffView.Note> noteModel;
    private StaffView staff;
    private Button randomTune;
    private Button cmajor;
    private Button gmajor;
    private Button dmajor;
    private Button fmajor;
    private Button bbmajor;
    private CenterOffsetView ledview;
    private Button startbutton;
    private TextView instructions;
    private Button restartbutton;
    private MicrophonePitchPoster pitchPoster;
    private long startPracticeTime;

    private enum State {
        EMPTY_SCALE,     // initial state or after 'clear'
        WAIT_FOR_START,  // when notes are visible. Start button visible.
        PRACTICE,        // The game.
        FINISHED         // finished assignment.
    }

    public PracticeActivity() {
        noteModel = new ArrayList<StaffView.Note>();
    }

    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.practice);

        staff = (StaffView) findViewById(R.id.practiceStaff);
        staff.setNotesPerStaff(16);

        // For now we have a couple of buttons to create some basics, but
        // these should be replaced by: (a) Spinner (for choosing scales and randomTune)
        // and (b) direct editing.

        final NoteGenerationListener noteCreator = new NoteGenerationListener();
        randomTune = (Button) findViewById(R.id.newRandom);
        randomTune.setOnClickListener(noteCreator);
        cmajor = (Button) findViewById(R.id.newCMajor);
        cmajor.setOnClickListener(noteCreator);
        gmajor = (Button) findViewById(R.id.newGMajor);
        gmajor.setOnClickListener(noteCreator);
        dmajor = (Button) findViewById(R.id.newDMajor);
        dmajor.setOnClickListener(noteCreator);
        fmajor = (Button) findViewById(R.id.newFMajor);
        fmajor.setOnClickListener(noteCreator);
        bbmajor = (Button) findViewById(R.id.newBbMajor);
        bbmajor.setOnClickListener(noteCreator);
        ledview = (CenterOffsetView) findViewById(R.id.practiceLedDisplay);
        ledview.setKeepScreenOn(true);
        startbutton = (Button) findViewById(R.id.practiceStartButton);
        startbutton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                doPractice();
            }
        });
        restartbutton = (Button) findViewById(R.id.practiceRestartButton);
        restartbutton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                stopSampling();
                setActivityState(State.EMPTY_SCALE);
                setActivityState(State.WAIT_FOR_START);  // We already have notes
            }
        });
        instructions = (TextView) findViewById(R.id.practiceInstructions);

        staff.setNoteModel(noteModel);
        staff.setKeyDisplay(1);
        setActivityState(State.EMPTY_SCALE);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (pitchPoster != null) {
            setActivityState(State.FINISHED);
        }
    }

        // Kinda hardcoded now :)
    private final class NoteGenerationListener implements View.OnClickListener {
        public void onClick(View button) {
            boolean wantsFlat = false;
            int currentStartNote = 0;
            // We use the current start note to help toggle major scales.
            if (noteModel.size() > 0)
                currentStartNote = noteModel.get(0).pitch;
            noteModel.clear();
            if (button == randomTune) {
                for (int i = 0; i < 16; ++i) {
                    noteModel.add(new StaffView.Note(
                            3 + (int) (16 * Math.random()),
                            4, Color.BLACK));
                }
            } else if (button == cmajor) {
                addAscDescMajorScale(currentStartNote == 3 ? 15 : 3, noteModel);
            } else if (button == gmajor) {
                addAscDescMajorScale(currentStartNote == 10 ? 22 : 10, noteModel);
            } else if (button == dmajor) {
                addAscDescMajorScale(currentStartNote == 5 ? 17 : 5, noteModel);
            } else if (button == fmajor) {
                addAscDescMajorScale(currentStartNote == 8 ? 20 : 8, noteModel);
                wantsFlat = true;
            } else if (button == bbmajor) {
                addAscDescMajorScale(currentStartNote == 13 ? 25 : 13, noteModel);
                wantsFlat = true;
            }

            staff.ensureNoteInView(0);
            staff.setKeyDisplay(wantsFlat ? 0 : 1);
            setActivityState(noteModel.size() > 0
                                     ? State.WAIT_FOR_START
                                     : State.EMPTY_SCALE);
            staff.onModelChanged();
        }
    }

    private void doPractice() {
        if (noteModel.size() == 0) return;
        setActivityState(State.PRACTICE);
        // Todo: put all things related to the game into thing.
        pitchPoster = new MicrophonePitchPoster(60);
        pitchPoster.setHandler(new PitchFollowHandler(noteModel));
        pitchPoster.start();
        startPracticeTime = System.currentTimeMillis();
    }

    private void endPractice() {
        stopSampling();
        long duration = System.currentTimeMillis() - startPracticeTime;
        instructions.setText(String.format("%3.1f seconds!", duration / 1000.0));
        setActivityState(State.FINISHED);
    }

    private void stopSampling() {
        if (pitchPoster != null) {
            pitchPoster.stopSampling();
            pitchPoster = null;
        }
    }

    // Some abstraction of progress.
    private interface ProgressProvider {
        int getMaxProgress();
        int getCurrentProgress();
    }

    private class PitchFollowHandler extends Handler implements ProgressProvider {
        PitchFollowHandler(List<StaffView.Note> model) {
            highlightAnnotator = new HighlightAnnotator(this);
            running = true;
            this.model = model;
            for (StaffView.Note n : model) {
                n.color = futureNoteColor;
            }
            modelPos = -1;
            checkNextNote();
        }

        // --- interface ProgressProvider
        public int getMaxProgress() { return kHoldTime; }
        public int getCurrentProgress() { return ticksInTune; }

        public void handleMessage(Message msg) {
            if (!running)
                return;  // Received a sample, but we're done already.
            final MicrophonePitchPoster.PitchData data
                    = (MicrophonePitchPoster.PitchData) msg.obj;
            int beforeTicks = ticksInTune;

            boolean noteOk = data != null
                    && (data.note % 12 == model.get(modelPos).pitch % 12);
            ledview.setVisibility(noteOk ? View.VISIBLE : View.INVISIBLE);
            if (noteOk) {
                ledview.setValue(data.cent);
            }
            if (noteOk && Math.abs(data.cent) < kCentThreshold) {
                ++ticksInTune;
            } else if (data != null) {   // No data is not a penalty.
                --ticksInTune;
                if (ticksInTune < 0) ticksInTune = 0;
            }

            if (ticksInTune == 0) {
                instructions.setText("Find the note.");
            }
            else if (ticksInTune > 0 && ticksInTune < kHoldTime) {
                instructions.setText("Alright, now hold..");
            }
            else if (ticksInTune >= kHoldTime) {
                instructions.setText("Yay, continue!");
                checkNextNote();
            }
            if (beforeTicks != ticksInTune) {
                staff.onModelChanged();  // force redraw ('clock')
            }
        }

        private void checkNextNote() {
            StaffView.Note currentNote;
            if (modelPos >= 0) {
                currentNote = model.get(modelPos);
                currentNote.color = successNoteColor;
                currentNote.annotator = null;
            }

            ++modelPos;
            if (modelPos >= model.size()) {
                running = false;
                endPractice();
                staff.onModelChanged();  // post the last change.
                return;
            }
            currentNote = model.get(modelPos);
            currentNote.color = playNoteColor;
            currentNote.annotator = highlightAnnotator;
            ticksInTune = 0;
            staff.ensureNoteInView(modelPos);
            staff.onModelChanged();
        }

        private final static int kHoldTime = 15;
        private final HighlightAnnotator highlightAnnotator;
        private final List<StaffView.Note> model;
        private int modelPos;
        private int ticksInTune;
        private boolean running;
    }

    private void setGeneratorButtonsVisibility(int visibility) {
        randomTune.setVisibility(visibility);
        gmajor.setVisibility(visibility);
        cmajor.setVisibility(visibility);
        dmajor.setVisibility(visibility);
        fmajor.setVisibility(visibility);
        bbmajor.setVisibility(visibility);
    }

    private void setActivityState(State state) {
        switch (state) {
            case EMPTY_SCALE:
                startbutton.setVisibility(View.INVISIBLE);
                restartbutton.setVisibility(View.INVISIBLE);
                ledview.setVisibility(View.INVISIBLE);
                setGeneratorButtonsVisibility(View.VISIBLE);
                instructions.setText("Choose your chant of doom.");
                break;
            case WAIT_FOR_START:
                for (StaffView.Note n : noteModel) {
                    n.color = Color.BLACK;
                    n.annotator = null;
                }
                staff.ensureNoteInView(0);
                staff.onModelChanged();
                instructions.setText("Refine or start.");
                startbutton.setVisibility(View.VISIBLE);
                setGeneratorButtonsVisibility(View.VISIBLE);
                break;
            case PRACTICE:
                startbutton.setVisibility(View.INVISIBLE);
                restartbutton.setVisibility(View.VISIBLE);
                ledview.setVisibility(View.VISIBLE);
                setGeneratorButtonsVisibility(View.INVISIBLE);
                break;
            case FINISHED:
                stopSampling();
                startbutton.setVisibility(View.INVISIBLE);
                restartbutton.setVisibility(View.VISIBLE);
                ledview.setVisibility(View.INVISIBLE);
                setGeneratorButtonsVisibility(View.INVISIBLE);
        }
    }
    // Add a major scale to the model.
    private int addMajorScale(int startNote, boolean ascending,
                              List<StaffView.Note> model) {
        int note = startNote;
        model.add(new StaffView.Note(note, 4, Color.BLACK));
        for (int i = 0; i < kMajorScaleSequence.length; ++i) {
            if (ascending) {
                note += kMajorScaleSequence[i];
            } else {
                note -= kMajorScaleSequence[kMajorScaleSequence.length - 1 - i ];
            }
            model.add(new StaffView.Note(note, 4, Color.BLACK));
        }
        return note;
    }

    private void addAscDescMajorScale(int startNote, List<StaffView.Note> model) {
        addMajorScale(addMajorScale(startNote, true, model), false, model);
    }

    private static final class HighlightAnnotator
            implements StaffView.Note.Annotator {
        private final Paint highlightPaint;
        private final Paint borderPaint;
        private final Paint progressPaint;
        private final ProgressProvider progressProvider;

        public HighlightAnnotator(ProgressProvider progress) {
            highlightPaint = new Paint();
            highlightPaint.setColor(Color.argb(70, 0xff, 0xff, 0));
            highlightPaint.setStrokeWidth(0);
            borderPaint = new Paint();
            borderPaint.setColor(Color.BLACK);
            borderPaint.setStrokeWidth(0);
            borderPaint.setStyle(Paint.Style.STROKE);
            borderPaint.setAntiAlias(true);
            progressPaint = new Paint();
            progressPaint.setColor(Color.GREEN);
            progressProvider = progress;
        }

        public void draw(Canvas canvas, RectF staffBoundingBox,
                         RectF noteBoundingBox) {
            float lineWidth = (staffBoundingBox.bottom - staffBoundingBox.top)/4;
            RectF drawBox = new RectF(noteBoundingBox);
            // If note does not go outside staff, make the box a bit larger.
            drawBox.union(drawBox.left - 0.2f * lineWidth,
                          staffBoundingBox.top - lineWidth);
            drawBox.union(drawBox.right + 0.2f * lineWidth,
                          staffBoundingBox.bottom + lineWidth);
            float radius = drawBox.width() / 3;
            canvas.drawRoundRect(drawBox, radius, radius, highlightPaint);
            canvas.drawRoundRect(drawBox, radius, radius, borderPaint);

            float centerY;
            float clearanceBottom = canvas.getHeight() - drawBox.bottom;
            if (clearanceBottom > drawBox.top) {
                centerY = drawBox.bottom + clearanceBottom / 2;
            } else {
                centerY = drawBox.top / 2;
            }
            float timerRadius = lineWidth;
            float centerX = drawBox.left + (drawBox.right - drawBox.left) / 2;
            RectF timerBox = new RectF(centerX - timerRadius, centerY - timerRadius,
                                       centerX + timerRadius, centerY + timerRadius);
            float clockDegrees = 360.0f * progressProvider.getCurrentProgress()
                    / progressProvider.getMaxProgress();
            canvas.drawArc(timerBox, -90, clockDegrees, true, progressPaint);
            canvas.drawOval(timerBox, borderPaint);
        }
    }
}
