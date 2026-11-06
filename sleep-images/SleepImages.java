package local.codex.hibreakprobe;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.DisplayMetrics;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** The user's own sleep pictures (public build). Nothing is bundled with the app.
 * Pictures are copied, scaled to the panel and kept in device-protected app storage,
 * so the dream can show them even before the first unlock after a reboot.
 */
public final class SleepImages {
    private SleepImages() {}

    static File dir(Context ctx) {
        File dir=new File(ctx.getFilesDir(),"sleep-images");
        if(!dir.exists()) dir.mkdirs();
        return dir;
    }

    static List<File> list(Context ctx) {
        File[] files=dir(ctx).listFiles((d,name) -> name.endsWith(".png"));
        if(files==null) return new ArrayList<>();
        Arrays.sort(files);
        return new ArrayList<>(Arrays.asList(files));
    }

    /** Copies one picture, scaled to fit the screen. */
    static void add(Context ctx, Uri uri) throws IOException {
        DisplayMetrics screen=ctx.getResources().getDisplayMetrics();
        int maxW=Math.max(screen.widthPixels,1), maxH=Math.max(screen.heightPixels,1);
        BitmapFactory.Options bounds=new BitmapFactory.Options();
        bounds.inJustDecodeBounds=true;
        try(InputStream in=ctx.getContentResolver().openInputStream(uri)) { BitmapFactory.decodeStream(in,null,bounds); }
        if(bounds.outWidth<=0 || bounds.outHeight<=0) throw new IOException("Not a picture");
        BitmapFactory.Options options=new BitmapFactory.Options();
        while(bounds.outWidth/(options.inSampleSize*2)>=maxW || bounds.outHeight/(options.inSampleSize*2)>=maxH)
            options.inSampleSize*=2;
        Bitmap decoded;
        try(InputStream in=ctx.getContentResolver().openInputStream(uri)) { decoded=BitmapFactory.decodeStream(in,null,options); }
        if(decoded==null) throw new IOException("Not a picture");
        float scale=Math.min(1f,Math.min(maxW/(float)decoded.getWidth(),maxH/(float)decoded.getHeight()));
        Bitmap fitted=scale<1f ? Bitmap.createScaledBitmap(decoded,Math.round(decoded.getWidth()*scale),
            Math.round(decoded.getHeight()*scale),true) : decoded;
        File out=new File(dir(ctx),"image-"+System.currentTimeMillis()+".png");
        try(FileOutputStream stream=new FileOutputStream(out)) {
            if(!fitted.compress(Bitmap.CompressFormat.PNG,100,stream)) throw new IOException("Could not save");
        }
    }

    static void clear(Context ctx) {
        for(File file:list(ctx)) file.delete();
    }

    /** Shown when the user has not picked any picture yet: plain text, no artwork. */
    static Bitmap placeholder(Context ctx) {
        DisplayMetrics screen=ctx.getResources().getDisplayMetrics();
        int w=Math.max(screen.widthPixels,1), h=Math.max(screen.heightPixels,1);
        Bitmap bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(bitmap);
        canvas.drawColor(Color.WHITE);
        TextPaint paint=new TextPaint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.BLACK);
        paint.setTextSize(28*screen.density);
        String text="Sleeping\n\nPick your own sleep picture in the Sleep images app.";
        int width=w-(int)(64*screen.density);
        StaticLayout layout=StaticLayout.Builder.obtain(text,0,text.length(),paint,width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER).build();
        canvas.translate((w-width)/2f,(h-layout.getHeight())/2f);
        layout.draw(canvas);
        return bitmap;
    }
}
