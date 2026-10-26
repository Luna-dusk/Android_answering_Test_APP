"""Build with the official Android SDK; no third-party dependencies or Gradle download needed."""
import os,pathlib,shutil,subprocess,sys,zipfile,xml.etree.ElementTree as ET

ROOT=pathlib.Path(__file__).resolve().parent
SDK=pathlib.Path(os.environ.get('ANDROID_HOME',r'G:\Program Files\Android\sdk'))
if not SDK.exists():SDK=ROOT.parent.parent/'work/android/sdk'
JAVA=pathlib.Path(os.environ.get('JAVA_HOME',r'F:\jdk17'))/'bin'
BUILD=ROOT/'.build';BUILD.mkdir(exist_ok=True)
TOOLS=SDK/'build-tools/35.0.0';ANDROID=SDK/'platforms/android-35/android.jar'

def run(*args):
    args=[str(a) for a in args]
    print('Running',pathlib.Path(args[0]).name,flush=True)
    subprocess.run(args,check=True,cwd=ROOT)

def build():
    for name in ['classes','generated','dex']:(BUILD/name).mkdir(exist_ok=True)
    run(TOOLS/'aapt2.exe','compile','--dir',ROOT/'app/src/main/res','-o',BUILD/'resources.zip')
    manifest=(ROOT/'app/src/main/AndroidManifest.xml').read_text(encoding='utf-8').replace('<manifest ','<manifest package="cn.embedded.quiz" ',1).replace('<application ','<application android:debuggable="true" ',1)
    (BUILD/'AndroidManifest.xml').write_text(manifest,encoding='utf-8')
    run(TOOLS/'aapt2.exe','link','-o',BUILD/'base.apk','-I',ANDROID,'--manifest',BUILD/'AndroidManifest.xml','--java',BUILD/'generated','--min-sdk-version','26','--target-sdk-version','35','--version-code','1','--version-name','1.0.0','-A',ROOT/'app/src/main/assets',BUILD/'resources.zip')
    sources=list((ROOT/'app/src/main/java').rglob('*.java'))+list((BUILD/'generated').rglob('*.java'))
    source_args='\n'.join('"'+str(p).replace('\\','/')+'"' for p in sources)
    (BUILD/'sources.txt').write_text(source_args,encoding='utf-8')
    run(JAVA/'javac.exe','-encoding','UTF-8','-source','17','-target','17','-classpath',ANDROID,'-d',BUILD/'classes','@'+str(BUILD/'sources.txt'))
    with zipfile.ZipFile(BUILD/'classes.jar','w') as z:
        for p in (BUILD/'classes').rglob('*.class'):z.write(p,p.relative_to(BUILD/'classes').as_posix())
    # Invoke the official D8 main class directly to keep paths with spaces predictable.
    run(JAVA/'java.exe','-cp',TOOLS/'lib/d8.jar','com.android.tools.r8.D8','--lib',ANDROID,'--min-api','26','--output',BUILD/'dex',BUILD/'classes.jar')
    shutil.copyfile(BUILD/'base.apk',BUILD/'unsigned.apk')
    with zipfile.ZipFile(BUILD/'unsigned.apk','a',zipfile.ZIP_DEFLATED) as z:
        for p in (BUILD/'dex').glob('*.dex'):z.write(p,p.name)
    run(TOOLS/'zipalign.exe','-f','4',BUILD/'unsigned.apk',BUILD/'aligned.apk')
    key=BUILD/'development.keystore'
    if not key.exists():run(JAVA/'keytool.exe','-genkeypair','-keystore',key,'-storepass','android','-keypass','android','-alias','androiddebugkey','-dname','CN=Embedded Quiz Development','-keyalg','RSA','-keysize','2048','-validity','10000')
    out=ROOT.parent/'嵌入式刷题.apk'
    run(JAVA/'java.exe','-jar',TOOLS/'lib/apksigner.jar','sign','--ks',key,'--ks-pass','pass:android','--key-pass','pass:android','--out',out,BUILD/'aligned.apk')
    run(JAVA/'java.exe','-jar',TOOLS/'lib/apksigner.jar','verify','--verbose',out)
    print('APK:',out,flush=True)

if __name__=='__main__':build()
