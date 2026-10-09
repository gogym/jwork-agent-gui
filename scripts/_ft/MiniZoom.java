import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Throwaway helper: crop regions of a probe PNG and scale them 4x (nearest
 * neighbour) so emoji glyph shapes/colors can be inspected at pixel level.
 * Usage: java MiniZoom.java <src.png> <x> <y> <w> <h> <dst.png>
 */
public final class MiniZoom {
    public static void main(String[] args) throws Exception {
        BufferedImage src = ImageIO.read(new File(args[0]));
        int x = Integer.parseInt(args[1]), y = Integer.parseInt(args[2]);
        int w = Integer.parseInt(args[3]), h = Integer.parseInt(args[4]);
        int s = 4;
        BufferedImage dst = new BufferedImage(w * s, h * s, BufferedImage.TYPE_INT_ARGB);
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                int rgb = src.getRGB(x + i, y + j);
                for (int jj = 0; jj < s; jj++) {
                    for (int ii = 0; ii < s; ii++) {
                        dst.setRGB(i * s + ii, j * s + jj, rgb);
                    }
                }
            }
        }
        ImageIO.write(dst, "png", new File(args[5]));
        System.out.println("ZOOM_OK " + args[5]);
    }
}
