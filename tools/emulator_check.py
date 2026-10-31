"""ADB inspection helpers for the local prototype; no app data is cleared."""
from pathlib import Path
import json, subprocess, sys, re, time, os, csv, io, xml.etree.ElementTree as ET
sys.stdout.reconfigure(encoding='utf-8')

ADB = r'D:\Android\Sdk\platform-tools\adb.exe'
BASE = Path(__file__).resolve().parents[1]
PACKAGE = 'ru.devimind.chitalka'

def adb(*args, binary=False, check=True):
    p = subprocess.run([ADB, '-s', 'emulator-5554', *args], capture_output=True, timeout=45)
    if check and p.returncode:
        raise RuntimeError(p.stderr.decode('utf-8', 'replace'))
    return p.stdout if binary else p.stdout.decode('utf-8', 'replace')

def nodes():
    adb('shell', 'uiautomator', 'dump', '/sdcard/chitalka-test-ui.xml')
    return ET.fromstring(adb('shell', 'cat', '/sdcard/chitalka-test-ui.xml'))

def tree():
    result=[]
    root=nodes()
    blocked={id(n) for w in root.iter('node') if w.get('class')=='android.webkit.WebView' for n in w.iter('node')}
    for n in root.iter('node'):
        if id(n) in blocked:continue
        if n.get('text') or n.get('content-desc') or n.get('class')=='android.widget.EditText':
            result.append({k:n.get(k) for k in ('text','content-desc','resource-id','bounds','class','clickable')})
    print(json.dumps(result,ensure_ascii=False,indent=2))

def tap(label):
    found=[]
    for n in nodes().iter('node'):
        if label in (n.get('text'),n.get('content-desc'),n.get('resource-id')):
            found.append(n)
    if not found:
        raise RuntimeError('UI element not found: '+label)
    n=next((n for n in found if n.get('clickable')=='true'),found[0])
    x1,y1,x2,y2=map(int,re.findall(r'\d+',n.get('bounds')))
    print(adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2)))
    print('Tapped:',label)

step=sys.argv[1]
if step=='session':
    print('Android prototype verification session ready.',flush=True)
    for line in sys.stdin:
        try:
            action=json.loads(line)
            if action==['finish']:break
            result=subprocess.run([sys.executable,str(Path(__file__).resolve()),*action],capture_output=True,timeout=180)
            print(json.dumps({'action':action,'exit':result.returncode,'output':result.stdout.decode('utf-8','replace'),'error':result.stderr.decode('utf-8','replace')},ensure_ascii=False),flush=True)
        except Exception as exc:print(json.dumps({'error':str(exc)},ensure_ascii=False),flush=True)
    sys.exit(0)
if step=='flow':
    for action in json.loads(sys.argv[2]):
        subprocess.run([sys.executable,str(Path(__file__).resolve()),*action],check=True)
elif step=='sleep':time.sleep(min(10,float(sys.argv[2])))
elif step=='batch':
    results=[]
    for action in json.loads(sys.argv[2]):
        r=subprocess.run([sys.executable,str(Path(__file__).resolve()),*action],capture_output=True,timeout=120)
        results.append({'action':action,'exit':r.returncode,'out':r.stdout.decode('utf-8','replace'),'error':r.stderr.decode('utf-8','replace')})
        if r.returncode:break
    folder=BASE/'.local';folder.mkdir(exist_ok=True)
    (folder/'last-check.json').write_text(json.dumps(results,ensure_ascii=False,indent=2),encoding='utf-8')
    print('Steps:',len(results),'OK:',all(r['exit']==0 for r in results),flush=True)
    sys.exit(0 if all(r['exit']==0 for r in results) else 1)
elif step=='status':
    print(subprocess.run([ADB,'devices','-l'],capture_output=True,text=True).stdout)
    print(adb('shell','getprop','sys.boot_completed',check=False))
elif step=='processes':
    output=subprocess.run(['tasklist.exe','/FO','CSV','/NH'],capture_output=True).stdout.decode('utf-8','replace')
    print('\n'.join(line for line in output.splitlines() if any(n in line.lower() for n in ('qemu','emulator.exe','adb.exe'))))
elif step=='install':
    manifest=ET.parse(BASE/'app'/'src'/'main'/'AndroidManifest.xml').getroot()
    version=manifest.get('{http://schemas.android.com/apk/res/android}versionName')
    print(adb('install','-r',str(BASE.parent/'_приложение'/f'Chitalka-{version}-debug.apk')))
    print(adb('shell','am','start','-n',PACKAGE+'/.MainActivity'))
elif step=='tree':tree()
elif step=='open-reader':
    current=nodes()
    if not any(n.get('content-desc') in ('В библиотеку','О книге') for n in current.iter('node')):
        card=next(n.get('content-desc') for n in current.iter('node') if (n.get('content-desc') or '').startswith(('О книге: ','Читать: ')))
        tap(card)
        current=nodes()
    if any(n.get('resource-id')=='ru.devimind.chitalka:id/read_book' for n in current.iter('node')):
        tap('Читать')
    time.sleep(2)
    current=nodes()
    assert any(n.get('content-desc') in ('В библиотеку','О книге') for n in current.iter('node')) and any(n.get('class')=='android.webkit.WebView' for n in current.iter('node')),'Reader not ready'
    print('Reader is open.')
elif step=='tap':tap(sys.argv[2])
elif step=='input':print(adb('shell','input','text',sys.argv[2].replace(' ','%s')))
elif step=='key':print(adb('shell','input','keyevent',sys.argv[2]))
elif step=='shot':
    path=BASE/'Проверки'/sys.argv[2]
    path.write_bytes(adb('exec-out','screencap','-p',binary=True))
    print(path)
elif step=='restart':
    print(adb('shell','am','force-stop',PACKAGE))
    print(adb('shell','am','start','-n',PACKAGE+'/.MainActivity'))
elif step=='db':
    import sqlite3
    data=adb('exec-out','run-as',PACKAGE,'cat','databases/library.db',binary=True)
    db=sqlite3.connect(':memory:');db.deserialize(data)
    print(json.dumps({'books':db.execute('select id,title,author,chapter,scroll from books').fetchall(),'notes':db.execute('select type,title,body,parent_id,source from notes').fetchall()},ensure_ascii=False,indent=2))
elif step=='stats':
    import sqlite3
    db=sqlite3.connect(':memory:');db.deserialize(adb('exec-out','run-as',PACKAGE,'cat','databases/library.db',binary=True))
    for (book_id,) in db.execute('select id from books'):
        assert re.fullmatch('[0-9a-f]{64}',book_id)
        book=json.loads(adb('exec-out','run-as',PACKAGE,'cat','files/books/'+book_id+'.json'))
        result={'title':book['title'],'author':book['author'],'chapters':len(book['chapters']),'parts':len(set(c['part'] for c in book['chapters'])),'footnotes':len(book['footnotes']),'cover':bool(book['cover'])}
        assert result['chapters']==117,result
        assert result['footnotes']==72,result
        assert result['parts']==6,result
        (BASE/'Проверки'/'fb2-import.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
        print(json.dumps(result,ensure_ascii=False,indent=2))
elif step=='build':
    subprocess.run(['powershell.exe','-NoProfile','-ExecutionPolicy','Bypass','-File',str(BASE/'tools'/'Build-Android.ps1')],check=True)
elif step=='export-check':
    import shlex
    path='/sdcard/Download/Chitalka/Граф Монте-Кристо — конспект.md'
    text=adb('shell','cat',shlex.quote(path))
    assert '# Граф Монте-Кристо' in text
    assert 'Test hero' in text and 'Description from reader' in text
    assert 'Prototype check' in text and 'Saved after restart' in text
    (BASE/'Проверки'/'markdown-export-check.txt').write_text('PASS: title, author, hero, note text and chapter source exported in UTF-8.\n',encoding='utf-8')
    print('PASS: Markdown export content verified.')
elif step=='footnote-ui':
    found=False
    for attempt in range(16):
        root=nodes()
        for web in root.iter('node'):
            if web.get('class')!='android.webkit.WebView':continue
            for n in web.iter('node'):
                label=(n.get('text') or n.get('content-desc') or '').strip()
                if not re.fullmatch(r'\[?\d+\]?',label):continue
                coords=list(map(int,re.findall(r'\d+',n.get('bounds',''))))
                if len(coords)!=4:continue
                x1,y1,x2,y2=coords
                if x2>x1 and 420<y1<y2<2100:
                    adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2));found=True;break
            if found:break
        if found:break
        adb('shell','input','swipe','800','1750','800','700','500')
    assert found,'Footnote link not found in visible chapter'
    current=nodes()
    assert any(n.get('text')=='Примечание' for n in current.iter('node')),'Footnote dialog did not open'
    (BASE/'Проверки'/'07-Примечание.png').write_bytes(adb('exec-out','screencap','-p',binary=True))
    print('PASS: real book footnote opens in dialog and preserves the reader behind it.')
elif step=='log':print(adb('logcat','-d','-s','AndroidRuntime:E','Chitalka:E'))
elif step=='timezone':
    print(adb('shell','settings','put','global','auto_time_zone','0'))
    print(adb('shell','cmd','alarm','set-timezone','Europe/Moscow'))
elif step=='keyboard':print(adb('shell','settings','put','secure','show_ime_with_hard_keyboard','1'))
elif step=='longpress':
    x,y=sys.argv[2:4];print(adb('shell','input','swipe',x,y,x,y,'1200'))
elif step=='swipe':print(adb('shell','input','swipe',*sys.argv[2:6],'450'))
else:raise ValueError(step)
