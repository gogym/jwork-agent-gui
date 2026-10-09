$ErrorActionPreference = 'Stop'
$m2root = 'D:\Program Files\maven-mvnd-1.0.6-windows-amd64\m2\org\xerial\sqlite-jdbc'
$jar = Get-ChildItem -Path $m2root -Recurse -Filter 'sqlite-jdbc-*.jar' | Where-Object { $_.Name -notmatch 'sources|javadoc' } | Select-Object -First 1 -ExpandProperty FullName
Write-Output "JAR=$jar"
$dir = 'D:\GitHub\gim-agent-gui\scripts\_ft'
$java = 'D:\Program Files\BellSoft\jdk-21.0.8\bin\java.exe'
$src = Join-Path $dir 'Q.java'
@'
import java.sql.*;
import java.io.*;
public class Q {
  public static void main(String[] a) throws Exception {
    Connection c = DriverManager.getConnection("jdbc:sqlite:C:/Users/Administrator/.jwork/jwork.db");
    BufferedWriter w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream("D:/GitHub/gim-agent-gui/scripts/_ft/qout.txt"), "UTF-8"));
    Statement st = c.createStatement();
    ResultSet rs = st.executeQuery("select role, substr(content,1,500), created_at from conversations order by id desc limit 24");
    while (rs.next()) {
      w.write("== " + rs.getString(1) + " [" + rs.getString(3) + "]");
      w.newLine();
      w.write(String.valueOf(rs.getString(2)));
      w.newLine();
    }
    w.close(); c.close();
    System.out.println("DONE");
  }
}
'@ | Set-Content -Path $src -Encoding ASCII
& $java -cp $jar $src
