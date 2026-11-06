#!/usr/bin/env python3
"""Builds assets/fixes.txt ("typo fix" per line): sure-thing corrections applied on space even when the typo happens to be
in the frequency list (subtitles contain "ts", "thier", ...).

Sources:
- dict-source/wikipedia_common_misspellings.txt = Wikipedia:Lists of common misspellings/For machines
  (https://en.wikipedia.org/wiki/Wikipedia:Lists_of_common_misspellings/For_machines), CC BY-SA 4.0, Wikipedia contributors.
- TYPING below: phone-keyboard slips (swapped letters, missed letters, missing apostrophes), written for Fast Keys.
A fix is kept only if the target is in assets/words.txt and the typo isn't itself a common word (count >= target / 20).
"""
import os, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
WIKI = os.path.join(ROOT, "dict-source", "wikipedia_common_misspellings.txt")
WORDS = os.path.join(ROOT, "assets", "words.txt")
OUT = os.path.join(ROOT, "assets", "fixes.txt")

TYPING = """
ts its | tat that | adn and | nad and | teh the | hte the | thw the | tge the | taht that | thta that | tht that | jsut just | juts just
jstu just | waht what | whta what | hwat what | wnat want | wnated wanted | yuo you | oyu you | yoy you | yoru your | yuor your
ot to | fo of | ofr for | fro for | nto not | tno not | liek like | lkie like | knwo know | konw know | kwno know | nkow know
didnt didn't | doesnt doesn't | isnt isn't | wasnt wasn't | couldnt couldn't | wouldnt wouldn't | shouldnt shouldn't
havent haven't | hasnt hasn't | hadnt hadn't | arent aren't | werent weren't | youre you're | theyre they're | thats that's
whats what's | theres there's | heres here's | ive I've | im I'm | dont don't | cant can't | wont won't | youve you've
becuase because | beacuse because | becasue because | beause because | thier their | recieve receive | wierd weird
tommorow tomorrow | tomorow tomorrow | tommorrow tomorrow | definately definitely | definatly definitely | seperate separate
untill until | wich which | whcih which | wiht with | wtih with | iwth with | hwo how | tahn than | thne then | ahve have
hvae have | haev have | sya say | cna can | nwo now | btu but | bnut but | smae same | soem some | somthing something
sometihng something | eveything everything | evrything everything | probaly probably | realy really | actualy actually
basicly basically | finaly finally | beleive believe | belive believe | freind friend | frined friend | goign going
giong going | gonig going | thnk think | thikn think | thign thing | thigns things | peopel people | poeple people
pepole people | tiem time | mroe more | mkae make | amke make | ahd had | hda had | wsa was | aws was | ehr her | hsi his
sohuld should | shoudl should | woudl would | coudl could | cuold could | rihgt right | rigth right | abotu about
aobut about | baout about | oen one | laso also | aslo also | alos also | tehy they | htey they | tehm them | thme them
yeha yeah | okya okay | oaky okay | agian again | agin again | alraedy already | alredy already | aroudn around
befroe before | befor before | cmoe come | coem come | dya day | evne even | fidn find | gerat great | grat great
godo good | goood good | hapy happy | hepl help | hlep help | hoem home | homw home | lsat last | lvoe love | loev love
mabye maybe | mybe maybe | mihgt might | mnay many | msut must | nevr never | nihgt night | ogt got | olny only | onyl only
otehr other | ohter other | pleae please | plase please | pelase please | sitll still | stil still | suer sure | sur sure
tahnk thank | thakn thank | thnaks thanks | thansk thanks | tihs this | thsi this | htis this | tiwh with
tis this | tonihgt tonight | uor our | vrey very | veyr very | wahts what's | watn want | wehn when | whne when | wher where
whihc which | whiel while | wokr work | wrok work | worl world | wrold world | yaer year | yera year | yse yes | yeas yes
"""

words = {}
with open(WORDS, encoding="utf-8") as f:
    for line in f:
        w, c = line.split()
        words[w] = int(c)

fixes = {}
def add(typo, fix):
    typo = typo.strip().lower()
    fix = fix.strip()
    if not typo or not fix or " " in fix or "," in fix or typo == fix.lower():
        return
    target = fix.lower()
    if target not in words:
        return
    if words.get(typo, 0) * 20 >= words[target]:
        return   # the "typo" is a real word in its own right
    fixes[typo] = fix

with open(WIKI, encoding="utf-8") as f:
    for line in f:
        if "->" in line:
            typo, fix = line.strip().split("->", 1)
            add(typo, fix)
for pair in TYPING.replace("\n", "|").split("|"):
    if pair.strip():
        typo, fix = pair.split()
        add(typo, fix)

with open(OUT, "w", encoding="utf-8") as f:
    for typo in sorted(fixes):
        f.write(f"{typo} {fixes[typo]}\n")
print(f"{len(fixes)} fixes -> {OUT}", file=sys.stderr)
