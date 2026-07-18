import hashlib, json, os

BUILDS = os.environ.get('GITHUB_WORKSPACE', '') + '/builds'

plugins = [
    {
        'name': 'DiziBox', 'internalName': 'DiziBox', 'version': 24,
        'description': 'DiziBox platformu.',
        'iconUrl': 'https://www.google.com/s2/favicons?domain=www.dizibox.live&sz=%size%',
        'tvTypes': ['TvSeries'], 'language': 'tr',
        'authors': ['programmer'], 'status': 1
    },
    {
        'name': 'Dizilla', 'internalName': 'Dizilla', 'version': 97,
        'description': 'Dizilla platformu.',
        'iconUrl': 'https://www.google.com/s2/favicons?domain=dizillahd.com&sz=%size%',
        'tvTypes': ['TvSeries'], 'language': 'tr',
        'authors': ['programmer'], 'status': 1
    },
    {
        'name': 'DiziPal', 'internalName': 'DiziPal', 'version': 91,
        'description': 'DiziPal platformu.',
        'iconUrl': 'https://www.google.com/s2/favicons?domain=https://dizipal952.com&sz=%size%',
        'tvTypes': ['TvSeries', 'Movie'], 'language': 'tr',
        'authors': ['programmer', 'muratcesmecioglu'], 'status': 1
    },
    {
        'name': 'DiziPalOriginal', 'internalName': 'DiziPalOriginal', 'version': 68,
        'description': 'DiziPalOriginal platformu.',
        'iconUrl': 'https://www.google.com/s2/favicons?domain=https://dizipal2036.com&sz=%size%',
        'tvTypes': ['TvSeries', 'Movie'], 'language': 'tr',
        'authors': ['programmer', 'muratcesmecioglu'], 'status': 1
    }
]

result = []
for p in plugins:
    cs3 = p['internalName'] + '.cs3'
    path = BUILDS + '/' + cs3
    if not os.path.exists(path):
        print('UYARI: ' + cs3 + ' bulunamadi, atlaniyor')
        continue
    with open(path, 'rb') as f:
        h = hashlib.sha256(f.read()).hexdigest()
    size = os.path.getsize(path)
    entry = {
        'url': 'https://raw.githubusercontent.com/programmrm/Metro/builds/' + cs3,
        'status': p['status'],
        'version': p['version'],
        'name': p['name'],
        'internalName': p['internalName'],
        'authors': p['authors'],
        'description': p['description'],
        'fileSize': size,
        'repositoryUrl': 'https://github.com/programmrm/Metro',
        'language': p['language'],
        'tvTypes': p['tvTypes'],
        'iconUrl': p['iconUrl'],
        'apiVersion': 1,
        'fileHash': 'sha256-' + h
    }
    result.append(entry)

with open(BUILDS + '/plugins.json', 'w') as f:
    json.dump(result, f, ensure_ascii=False, indent=4)
print('plugins.json olusturuldu (' + str(len(result)) + ' plugin)')
