package local.codex.hibreakprobe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** A persisted shuffle bag: visit every image before reshuffling, without a boundary repeat. */
public final class SleepImageDeck {
    public static final String[] FILES = {
        "Bird.png", "Dance.png", "Dead.png", "Duck.png", "Hell Yeah.png",
        "Hole.png", "Reading.png", "Sheet Music.png", "Steps.png", "Whole World.png"
    };
    public static final class Pick {
        public final int index;
        public final String remaining;
        private Pick(int index, String remaining) { this.index=index; this.remaining=remaining; }
    }

    public static Pick next(String saved, int last, Random random) {
        return next(saved, last, FILES.length, random);
    }

    /** Same shuffle bag over any number of images (the public build uses the user's own). */
    public static Pick next(String saved, int last, int count, Random random) {
        if(count==1) return new Pick(0,"");
        List<Integer> queue=new ArrayList<>();
        try {
            if(saved!=null && !saved.isEmpty()) {
                for(String part:saved.split(",",-1)) {
                    int value=Integer.parseInt(part);
                    if(value<0 || value>=count || queue.contains(value) || value==last)
                        throw new IllegalArgumentException("Invalid saved image deck");
                    queue.add(value);
                }
            }
        } catch(IllegalArgumentException invalid) { queue.clear(); }
        if(queue.isEmpty()) {
            for(int i=0;i<count;i++) queue.add(i);
            Collections.shuffle(queue,random);
            if(queue.get(0)==last) Collections.swap(queue,0,1+random.nextInt(queue.size()-1));
        }
        int selected=queue.remove(0);
        StringBuilder remaining=new StringBuilder();
        for(int value:queue) {
            if(remaining.length()>0) remaining.append(',');
            remaining.append(value);
        }
        return new Pick(selected,remaining.toString());
    }
}
