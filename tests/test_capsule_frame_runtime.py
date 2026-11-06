"""The frame under the capsule: grown once, held, and put back.

While the capsule is worn, the picture TikTok stops at its own bar's edge is
grown down to the bar's bottom, and every container between it and the first
full-height one is opened so the growth is drawn rather than clipped away.
Three things there are worth a test, because none of them shows up as a crash:

* the growth is worked out once and then held -- a look every second that
  tore it down and raised it again put the frame back over the bar's strip,
  once a second, for as long as the app was open;
* a picture already grown still asks to be grown to the same place, because
  TikTok measures it by its own height, not by the one we gave it, and lays
  it out again for every new video;
* the growth is put back where TikTok would have had it, at the height it
  last gave the picture, rather than the height we left on it.

Runs the real `openUp`, `grownTo`, `grow` and `lower` from NavBar.java against
small doubles of the Android views around them.
"""

import shutil
import unittest

import test_settings_safety_runtime as safety

method = safety.method


def body():
    """The four methods, as they are written, with the fields they use."""
    fields = '''
    private android.view.TextureView raised;
    private int raisedWas;
    private int raisedTo;
    private final java.util.ArrayList<android.view.ViewGroup> opened = new java.util.ArrayList<android.view.ViewGroup>();
    private final java.util.ArrayList<Boolean> openedWas = new java.util.ArrayList<Boolean>();
    private final java.util.ArrayList<Boolean> openedPadWas = new java.util.ArrayList<Boolean>();
    private long raisedLooked;
    private static android.view.TextureView playing;
    private static android.view.TextureView largest(android.view.View root) { return playing; }
'''
    return fields + ''.join(method('NavBar.java', sign) for sign in (
        '        private void openUp(ViewGroup bar)',
        '        private int grownTo(android.view.TextureView tape, int bottom)',
        '        private void grow(android.view.TextureView tape, int target, int bottom)',
        '        private void lower()',
    ))


ANDROID = {
    'android/content/SharedPreferences.java': '''package android.content;
        public interface SharedPreferences {
        interface Editor { Editor putBoolean(String key, boolean value); void apply(); } }''',
    'android/os/SystemClock.java': '''package android.os;
        public class SystemClock { public static long now = 1000L; public static long uptimeMillis() { return now; } }''',
    'android/view/View.java': '''package android.view;
        public class View {
        public ViewGroup parent; public boolean attached = true, clips = true, padClips = true;
        public int top, bottom, width = 1080, height = 2400, invalidations, clipWrites, bottomWrites;
        public View() {} public View(int top, int bottom) { this.top = top; this.bottom = bottom; }
        public Object getParent() { return parent; }
        public View getRootView() { return parent == null ? this : parent.getRootView(); }
        public int getTop() { return top; } public int getBottom() { return bottom; }
        public void setBottom(int value) { bottom = value; bottomWrites++; }
        public int getHeight() { return bottom - top; }
        public int getWidth() { return width; }
        public boolean isAttachedToWindow() { return attached; }
        public void invalidate() { invalidations++; }
        public void getLocationOnScreen(int[] at) { at[0] = 0; at[1] = top; }
        public boolean getClipChildren() { return clips; } public void setClipChildren(boolean yes) { clips = yes; clipWrites++; }
        public boolean getClipToPadding() { return padClips; } public void setClipToPadding(boolean yes) { padClips = yes; clipWrites++; }
        }''',
    'android/view/ViewGroup.java': '''package android.view;
        public class ViewGroup extends View { public ViewGroup() {} public ViewGroup(int top, int bottom) { super(top, bottom); } }''',
    'android/view/TextureView.java': '''package android.view;
        public class TextureView extends View { public TextureView(int top, int bottom) { super(top, bottom); } }''',
}


HARNESS = '''
    static ViewGroup page() { return new ViewGroup(0, 2400); }
    static android.view.TextureView picture(ViewGroup parent, int top, int bottom) {
        android.view.TextureView tape = new android.view.TextureView(top, bottom);
        tape.parent = parent;
        return tape;
    }
    static void check(boolean it, String said) { if (!it) throw new AssertionError(said); }
    public static void main(String[] args) {
        // a page, a picture in it that stops at 3000, and a bar from 3000 to 3120
        ViewGroup inner = page(), outer = page();
        android.view.TextureView tape = picture(inner, 0, 3000);
        inner.parent = outer; outer.parent = page();
        playing = tape;
        Capsule part = new Capsule();
        ViewGroup bar = new ViewGroup(3000, 3120);
        bar.parent = outer;
        long started = android.os.SystemClock.now;
        part.openUp(bar);
        check(part.raised == tape, "the picture was not grown");
        check(part.raisedTo == 3120, "grown to " + part.raisedTo + " rather than the bar's bottom");
        check(tape.getBottom() == 3120, "the picture is at " + tape.getBottom());
        check(!inner.clips && !outer.clips, "the containers still clip the growth away");

        // a second later it is looked at again -- and nothing is taken apart
        int writes = tape.bottomWrites, clip = inner.clipWrites + outer.clipWrites;
        android.os.SystemClock.now = started + 1001;
        part.openUp(bar);
        part.openUp(bar);
        check(part.raised == tape, "the growth was let go of by a later look");
        check(tape.bottomWrites == writes, "the picture was rewritten by a look that had nothing to do");
        check(inner.clipWrites + outer.clipWrites == clip, "the clipping was rewritten by a look that had nothing to do");

        // TikTok lays the picture out again for the next video: it goes back to 3000
        tape.setBottom(3000);
        android.os.SystemClock.now = started + 5001;
        part.openUp(bar);
        check(tape.getBottom() == 3120, "a picture laid out again is not grown back");

        // the same picture, laid out shorter: the growth follows it
        tape.setBottom(2900);
        android.os.SystemClock.now = started + 7001;
        part.openUp(bar);
        check(tape.getBottom() == 3120, "a shorter picture is not grown to the same place");

        // another video takes its place
        android.view.TextureView next = picture(inner, 0, 2950);
        playing = next;
        android.os.SystemClock.now = started + 9001;
        part.openUp(bar);
        check(part.raised == next && next.getBottom() == 3120, "the new picture was not taken up");
        check(tape.getBottom() == 2900, "the old picture was left grown, at " + tape.getBottom());

        // the capsule goes, and the screen under it is taken apart as it does
        inner.attached = false;
        part.lower();
        check(part.raised == null, "the growth was not let go of");
        check(next.getBottom() == 2950, "the picture was not put back where TikTok had it");

        // a picture that already reaches the bar needs nothing done to it
        playing = picture(outer, 0, 3120);
        part.openUp(bar);
        check(part.raised == null, "a full-height picture was grown anyway");

        // and one that ends above the bar entirely is left alone too
        playing = picture(outer, 0, 2000);
        part.lower();
        android.os.SystemClock.now = started + 20001;
        part.openUp(bar);
        check(part.raisedTo == 3120 && playing.getBottom() == 3120, "a short picture is not grown to the bar");

        System.out.println("frame under the capsule: grown, held and put back");
    }
'''


class CapsuleFrameTest(unittest.TestCase):

    @unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'JDK required')
    def test_the_frame_is_grown_once_and_held(self):
        sources = dict(ANDROID)
        sources['Harness.java'] = ('public class Harness { static class Capsule {' + body()
                                   + '}' + HARNESS + '}')
        safety.SafetyRuntimeTest().run_java(sources)
        # the harness is compiled by the runner; it prints its own line, and a
        # failure inside it is a non-zero exit, which the runner treats as one


if __name__ == '__main__':
    unittest.main()
