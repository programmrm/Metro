import hashlib, json, os, re, zipfile

ROOT = os.environ.get('GITHUB_WORKSPACE', '') + '/src'
BUILDS = os.environ.get('GITHUB_WORKSPACE', '') + '/builds'

def parse_list_of(text):
    return re.findall(r'"([^"]+)"', text)

def parse_build_gradle(path):
    with open(path, encoding='utf-8') as f:
        content = f.read()

    version = int(re.search(r'version\s*=\s*(\d+)', content).group(1))
    block = re.search(r'cloudstream\s*\{([\s\S]*?)\}', content).group(1)

    def field(name):
        m = re.search(name + r'\s*=\s*(.+)', block)
        return m.group(1).strip() if m else ''

    return {
        'version': version,
        'authors': parse_list_of(field('authors')) or ['programmer'],
        'language': (field('language').strip('"') or 'tr'),
        'description': field('description').strip().strip('"'),
        'status': int(re.search(r'(\d+)', field('status')).group(1)) if field('status') else 1,
        'tvTypes': parse_list_of(field('tvTypes')) or ['Movie'],
        'iconUrl': field('iconUrl').strip().strip('"'),
    }

plugins = []
if os.path.isdir(ROOT):
    for name in sorted(os.listdir(ROOT)):
        gradle = os.path.join(ROOT, name, 'build.gradle.kts')
        if not os.path.isfile(gradle):
            continue
        try:
            meta = parse_build_gradle(gradle)
        except Exception as e:
            print(f'UYARI: {name} build.gradle.kts okunamadi: {e}')
            continue
        meta['name'] = name
        meta['internalName'] = name
        plugins.append(meta)

result = []
for p in plugins:
    cs3 = p['internalName'] + '.cs3'
    path = BUILDS + '/' + cs3
    if not os.path.exists(path):
        print('UYARI: ' + cs3 + ' bulunamadi, atlaniyor')
        continue

    # .cs3 manifest.json icindeki version tercih edilir
    version = p['version']
    try:
        with zipfile.ZipFile(path) as z:
            manifest = json.loads(z.read('manifest.json'))
            if 'version' in manifest:
                version = int(manifest['version'])
            if manifest.get('name'):
                p['name'] = manifest['name']
    except Exception:
        pass

    with open(path, 'rb') as f:
        h = hashlib.sha256(f.read()).hexdigest()
    size = os.path.getsize(path)
    entry = {
        'url': 'https://raw.githubusercontent.com/programmrm/Metro/builds/' + cs3,
        'status': p['status'],
        'version': version,
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
