#!/usr/bin/env python3
"""Chooses the static sticker set from Microsoft Fluent Emoji (MIT, Copyright (c) Microsoft Corporation) at a pinned commit and writes selection.json.
Run once with a checkout that has assets/*/metadata.json:  select.py /path/to/fluentui-emoji  (the result is committed; build-stickers.py uses it)."""
import json, os, re, sys

HERE = os.path.dirname(os.path.abspath(__file__))
deny = json.load(open(os.path.join(HERE, 'deny.json')))
DENY = [re.compile(p, re.I) for p in deny['patterns']]

def denied(group, cldr, keywords):
    if group in deny['groups']:
        return True
    text = ' | '.join([cldr] + list(keywords))
    return any(r.search(text) for r in DENY)

def words(*ws): return re.compile(r'\b(' + '|'.join(ws) + r')\b', re.I)

# a category is a (group, name pattern) pair; a name is matched on its CLDR name
RULES = [
    ('Happy',  'Smileys & Emotion', re.compile(r'(smiling|grinning|face with tears of joy|rolling on the floor|winking face$|relieved|slightly smiling|upside-down|star-struck|partying|cowboy|nerd|sunglasses|zany|thinking|hugging|shushing|saluting|melting|neutral|expressionless|sleeping|sleepy|pleading|crying|loudly crying|astonished|flushed|screaming|worried|confused|yawning|angry|pouting|grimacing|exploding|face with monocle|face with hand over mouth|face with open mouth|hushed|frowning|anguished|fearful|cold face|dizzy|clown|see-no|hear-no|speak-no|robot|smiling cat|grinning cat|face holding back|face with peeking|pensive|disappointed|downcast|persevering|weary|tired|hot|yawning)', re.I)),
    ('Happy',  'People & Body', words('thumbs up', 'clapping hands', 'waving hand', 'OK hand', 'victory hand', 'crossed fingers', 'raising hands', 'handshake', 'flexed biceps', 'love-you gesture', 'call me hand', 'raised hand', 'backhand index pointing up', 'folded hands'.replace('folded hands','muscle'))),
    ('Love',   'Symbols', re.compile(r'heart', re.I)),
    ('Love',   'Smileys & Emotion', re.compile(r'(hearts|heart-eyes|heart)', re.I)),
    ('Love',   'Objects', re.compile(r'(love letter|ring)$', re.I)),
    ('Love',   'Animals & Nature', re.compile(r'(rose|tulip|cherry blossom|hibiscus|blossom|bouquet|sunflower)', re.I)),
    ('Animals','Animals & Nature', re.compile(r'(face|dog|cat|mouse|hamster|rabbit|fox|bear|panda|koala|tiger|lion|cow|pig|frog|monkey|chicken|penguin|bird|duck|eagle|owl|bat|wolf|boar|horse|unicorn|bee|bug|butterfly|snail|ladybug|turtle|snake|lizard|octopus|crab|shrimp|squid|fish|dolphin|whale|shark|crocodile|leopard|zebra|gorilla|elephant|rhinoceros|hippopotamus|camel|giraffe|kangaroo|buffalo|ox|sheep|goat|deer|llama|dog|poodle|raccoon|badger|otter|sloth|skunk|hedgehog|squirrel|chipmunk|beaver|bison|mammoth|dodo|peacock|parrot|flamingo|swan|dove|rooster|chick|hatching|baby chick|turkey|seal|lobster|jellyfish|coral|ant|cricket|cockroach|spider|scorpion|mosquito|fly|worm|beetle|paw prints|dragon|dinosaur|T-Rex|sauropod)', re.I)),
    ('Food',   'Food & Drink', re.compile(r'.*')),
    ('Sports', 'Activities', re.compile(r'(ball|football|baseball|softball|basketball|volleyball|rugby|tennis|badminton|ice hockey|field hockey|lacrosse|cricket|golf|table tennis|boxing|skateboard|roller|ski|snowboard|sled|kite|yo-yo|fishing|running shirt|bowling|curling|martial arts|goal net|flying disc|diving mask|ice skate|kayak|canoe)', re.I)),
    ('Sports', 'Travel & Places', re.compile(r'(bicycle|kick scooter|roller skate)', re.I)),
    ('Party',  'Activities', re.compile(r'(party popper|confetti ball|balloon|wrapped gift|birthday|fireworks|sparkler|sparkles|trophy|medal|crown|ribbon|reminder ribbon|carousel|ferris|circus|performing|artist|musical|piñata|jigsaw|teddy bear|nesting dolls|video game|joystick|puzzle|yo-yo|pinata|paw)', re.I)),
    ('Party',  'Objects', re.compile(r'(guitar|violin|saxophone|trumpet|drum|microphone|headphone|musical|piano|accordion|banjo|bell|crown|balloon|gift|party|magic wand|camera|trophy|clapper)', re.I)),
    ('Party',  'Food & Drink', re.compile(r'(birthday cake|cupcake)', re.I)),
    ('Party',  'Travel & Places', re.compile(r'(fireworks|sparkler|carousel|ferris wheel|roller coaster|circus tent)', re.I)),
    ('Party',  'Smileys & Emotion', re.compile(r'(partying|party)', re.I)),
    ('Weather','Travel & Places', re.compile(r'(sun|cloud|rain|snow|rainbow|umbrella|lightning|tornado|fog|wind|thermometer|comet|moon|star|milky way|earth|globe|ocean|volcano|mountain|snowman|droplet|water wave|new moon|full moon|crescent|fire|sunrise|sunset|cityscape at dusk|night with stars|snowflake|high voltage|stars?$)', re.I)),
    ('Weather','Animals & Nature', re.compile(r'(sunflower|four leaf clover|palm tree|maple leaf|cactus|seedling|evergreen|deciduous|herb|leaf|fallen leaf|shamrock|potted plant|sheaf|cherry|bouquet|blossom|tulip|hibiscus|rose)', re.I)),
    ('Weather','Symbols', re.compile(r'(sparkle|glowing star|fire|dizzy)', re.I)),
]

# picked out by hand to keep the set friendly and a sensible size (not safety rules: those are in deny.json)
EXCLUDE = set("""spider|spider web|cockroach|mosquito|scorpion|snake|worm|fly|potted plant|beetle|bug|ant|amphora|baby bottle|bacon|beans|butter|canned food|chestnut|cut of meat|jar|lobster|mate|meat on bone|oyster|
pouring liquid|poultry leg|salt|shallow pan of food|squid|shrimp|tropical drink|crab|cooking|fondue|ice|tamale|pot of food|takeout box|stuffed flatbread|flatbread|falafel|oden|dango|fish cake with swirl|spoon|chopsticks|pea pod|olive|onion|garlic|
balloon|confetti ball|crystal ball|mirror ball|diving mask|yo-yo|running shirt|chess pawn|bell with slash|bullet train|high-speed train|train|mountain railway|mountain cableway|cityscape at dusk|foggy|fog|globe showing Americas|globe showing Asia-Australia|globe with meridians|
first quarter moon|first quarter moon face|last quarter moon|last quarter moon face|waxing crescent moon|waxing gibbous moon|waning crescent moon|waning gibbous moon|new moon|sheaf of rice|snowman without snow|sun behind large cloud|sun behind small cloud|sun behind rain cloud|
crying cat|pouting cat|weary cat|black bird|hatching chick|front-facing baby chick|service dog|guide dog|pig nose|dodo|mammoth|bison|sauropod|T-Rex|crocodile|exploding head|clown face|robot|grimacing face|melting face|face with peeking eye|saluting face|cold face|loudly crying face|anguished face|
heart exclamation|ring|rosette|camera|camera with flash|movie camera|video camera|clapper board|headphone|studio microphone|musical score|musical keyboard|long drum|accordion|banjo|nesting dolls|puzzle piece|artist palette|performing arts|reminder ribbon|sports medal|ribbon|wave|water wave""".replace("\n","").split("|"))

def main(repo):
    assets = os.path.join(repo, 'assets')
    seen, out = set(), []
    entries = []
    for d in sorted(os.listdir(assets)):
        p = os.path.join(assets, d, 'metadata.json')
        if not os.path.exists(p): continue
        m = json.load(open(p))
        if not os.path.exists(os.path.join(assets, d, 'metadata.json')): continue
        entries.append((d, m))
    for cat, group, rx in RULES:
        for d, m in entries:
            if d in seen or m['group'] != group: continue
            if not rx.search(m['cldr']) or m['cldr'] in EXCLUDE: continue
            # skin-tone variants carry a "Default" folder in the repo: only the plain ones have a 3D file at the top level (checked by build-stickers.py)
            if denied(m['group'], m['cldr'], m['keywords']): continue
            seen.add(d)
            out.append({'name': d, 'id': re.sub(r'[^a-z0-9]+', '-', m['cldr'].lower()).strip('-'), 'cldr': m['cldr'], 'keywords': m['keywords'], 'unicode': m.get('unicode', ''), 'cat': cat})
    json.dump({'source': 'https://github.com/microsoft/fluentui-emoji', 'commit': sys.argv[2] if len(sys.argv) > 2 else '', 'items': out}, open(os.path.join(HERE, 'selection.json'), 'w'), indent=1, ensure_ascii=False)
    from collections import Counter
    print(len(out), Counter(o['cat'] for o in out))

if __name__ == '__main__':
    main(sys.argv[1])
