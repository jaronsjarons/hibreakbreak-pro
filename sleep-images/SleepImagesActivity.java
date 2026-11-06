package local.codex.hibreakprobe;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Public build: pick the pictures shown while the phone sleeps. Black on white, no animation. */
public final class SleepImagesActivity extends Activity {
    private static final int PICK=1;
    private TextView status;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        int pad=dp(24);
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        root.setPadding(pad,pad*2,pad,pad);
        TextView title=text("Sleep images",28);
        TextView help=text("While the phone sleeps, the screen keeps showing one of your pictures. "
            +"Each sleep shows the next one. Pictures stay on this phone.",18);
        status=text("",18);
        Button add=button("Add pictures");
        add.setOnClickListener(v -> {
            Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("image/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);
            startActivityForResult(pick,PICK);
        });
        Button clear=button("Remove all pictures");
        clear.setOnClickListener(v -> new AlertDialog.Builder(this)
            .setMessage("Remove all sleep pictures from this app? The originals on your phone are not touched.")
            .setPositiveButton("Remove",(d,w) -> { SleepImages.clear(this); update(); })
            .setNegativeButton("Cancel",null).show());
        root.addView(title); root.addView(help); root.addView(status); root.addView(add); root.addView(clear);
        setContentView(root);
        update();
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        if(request!=PICK || result!=RESULT_OK || data==null) return;
        int added=0, failed=0;
        ClipData clip=data.getClipData();
        int count=clip!=null ? clip.getItemCount() : 1;
        for(int i=0;i<count;i++) {
            Uri uri=clip!=null ? clip.getItemAt(i).getUri() : data.getData();
            try { SleepImages.add(this,uri); added++; }
            catch(Exception e) { failed++; Log.w("HiBreakSleepImages","Could not add a picture",e); }
        }
        update();
        if(failed>0) status.append("\n"+failed+" could not be added.");
    }

    private void update() {
        int n=SleepImages.list(this).size();
        status.setText(n==0 ? "No pictures yet. The sleep screen shows a short note until you add one."
            : n+(n==1 ? " picture" : " pictures")+" chosen.");
    }

    private TextView text(String value, int sp) {
        TextView view=new TextView(this);
        view.setText(value); view.setTextColor(Color.BLACK);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP,sp);
        view.setPadding(0,0,0,dp(16));
        return view;
    }

    private Button button(String label) {
        Button button=new Button(this);
        button.setText(label); button.setAllCaps(false);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP,20);
        button.setGravity(Gravity.CENTER);
        return button;
    }

    private int dp(int value) { return Math.round(value*getResources().getDisplayMetrics().density); }
}
