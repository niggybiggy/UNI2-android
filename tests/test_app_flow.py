"""Compile/run the actual save importer against small host filesystem adapters.

These adapters exercise ZIP IO and backups; they do not emulate Android UI,
SAF providers, lifecycle or native game execution.
"""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "io/github/p4grecomp"
STUBS = {
    "android/net/Uri.java": '''package android.net;
public class Uri { public String path; public static Uri parse(String s) { Uri u=new Uri(); u.path=s; return u; } }''',
    "android/content/ContentResolver.java": '''package android.content;
public class ContentResolver {
 public java.io.InputStream openInputStream(android.net.Uri u) throws java.io.IOException {return new java.io.FileInputStream(u.path);}
 public android.database.Cursor query(android.net.Uri u,String[] p,String a,String[] b,String c){
  java.io.File root=new java.io.File(u.path);
  java.util.List<String[]> rows=new java.util.ArrayList<>();
  if(p.length==1) rows.add(new String[]{root.getName()});
  else {java.io.File[] files=root.listFiles(); if(files!=null) for(java.io.File f:files) rows.add(new String[]{f.getPath(),f.getName(),f.isDirectory()?"dir":"file"});}
  return new android.database.Cursor(){int row=-1;
   public boolean moveToFirst(){row=0;return !rows.isEmpty();}
   public boolean moveToNext(){return ++row<rows.size();}
   public String getString(int i){return rows.get(row)[i];}
   public void close(){}
  };
 }
}''',
    "android/database/Cursor.java": '''package android.database;
public interface Cursor extends java.io.Closeable {boolean moveToFirst(); boolean moveToNext(); String getString(int i); void close();}''',
    "android/os/Environment.java": '''package android.os;
public class Environment { public static final String DIRECTORY_DOWNLOADS="Downloads";
 public static java.io.File getExternalStoragePublicDirectory(String s){return new java.io.File(System.getProperty("p4g.test.root"),s);} }''',
    "android/provider/DocumentsContract.java": '''package android.provider;
public class DocumentsContract {
 public static class Document { public static final String COLUMN_DOCUMENT_ID="id",COLUMN_DISPLAY_NAME="name",COLUMN_MIME_TYPE="mime",MIME_TYPE_DIR="dir"; }
 public static String getTreeDocumentId(android.net.Uri u){return u.path;}
 public static android.net.Uri buildDocumentUriUsingTree(android.net.Uri u,String id){return android.net.Uri.parse(id);}
 public static android.net.Uri buildChildDocumentsUriUsingTree(android.net.Uri u,String id){return android.net.Uri.parse(id);}
}''',
    "android/util/Log.java": '''package android.util;
public class Log {public static int w(String tag,String message,Throwable error){return 0;}}''',
    PACKAGE + "/Launcher.java": '''package io.github.p4grecomp;
final class Launcher {static java.io.File dataDir(){return new java.io.File(System.getProperty("p4g.test.root"));}}''',
}

test_temp = (ROOT / "tests/.host-temp").resolve()
assert test_temp.is_relative_to(ROOT)
test_temp.mkdir(exist_ok=True)
with tempfile.TemporaryDirectory(prefix="p4g-java-tests-", dir=test_temp) as folder:
    temp = Path(folder).resolve()
    assert temp.is_relative_to(test_temp)
    sources = []
    for name, code in STUBS.items():
        file = temp / name
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(code, encoding="utf-8")
        sources.append(str(file))
    sources += [str(ROOT / "games/p4g/java" / PACKAGE / name) for name in
                ("SaveFiles.java", "SavePaths.java", "PreparationFiles.java", "InstalledGameFiles.java", "ModFiles.java")]
    sources.append(str(ROOT / "tests/AppFlowTest.java"))
    sources.append(str(ROOT / "tests/ModFilesTest.java"))
    subprocess.run(["javac", "-encoding", "UTF-8", "--release", "8", "-d", str(temp / "classes"), *sources], check=True)
    subprocess.run(["java", "-Djava.io.tmpdir=" + str(temp), "-cp", str(temp / "classes"), "io.github.p4grecomp.AppFlowTest"], check=True)
    subprocess.run(["java", "-Djava.io.tmpdir=" + str(temp), "-cp", str(temp / "classes"), "io.github.p4grecomp.ModFilesTest"], check=True)
