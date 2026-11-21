# Family Chat 1.0.0-rc4: test checklist (for the owner)

rc4 fixes the chat bubble layout. Install over rc3 (same key). Everything in the earlier checklists still applies.

1. **Width and side**: messages from others sit on the LEFT, the child's own (and anything sent from this phone) on the RIGHT. Each bubble is only as wide as its text; a long text wraps inside a bubble that is at most about 78% of the screen.
2. **Corners**: 20 dp rounded, with the small corner at the bottom left for others and bottom right for the child.
3. **Name and time**: the sender's name above others' bubbles in a group; the time under the child's bubble ("17:44", or "17:44 . edited"); "edited" under someone else's.
4. **Every kind**: a short text, a long text, a reply with a quote, a photo (bubble hugs the picture, picture about 220 dp wide), a video card, a sticker (no bubble, left or right), a GIF, a deleted message ("This message was deleted"), the reactions row (under the bubble, on the same side), the "Sent by <child>" line.
5. **Call notes**: "[call declined]" or "[missed call]" lines, if any appear, are small and centred.
6. **Big text**: set Android's font size to the largest: bubbles still wrap inside 78%, nothing is cut off.
7. **Landscape**: turn the phone sideways: bubbles are still 78% at most of the new width, still left/right.
