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
