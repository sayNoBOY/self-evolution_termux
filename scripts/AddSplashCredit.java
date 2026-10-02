import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * AddSplashCredit — 在智衍开屏合成图底部追加「制作人:老实人留一手」一行。
 * 无第三方依赖（本机无 PIL/ImageMagick，用 JDK 无头 AWT 渲染）。
 *
 * 用法: java AddSplashCredit <输入png> <输出png>
 */
public class AddSplashCredit {
    static final String TEXT = "制作人:老实人留一手";
    static final int FONT_SIZE = 42;
    static final int BASELINE_Y = 900;   // 原内容止于 y=805，留 ~50px 间距
    static final Color COLOR = new Color(0x8F, 0xA3, 0xB8); // 低调蓝灰，不抢主标语

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: java AddSplashCredit <in.png> <out.png>");
            System.exit(2);
        }
        BufferedImage src = ImageIO.read(new File(args[0]));
        Graphics2D g = src.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            Font font = new Font("Noto Sans CJK SC", Font.BOLD, FONT_SIZE);
            if (font.canDisplayUpTo(TEXT) != -1) {
                throw new IllegalStateException("font cannot render text: " + font.getFontName());
            }
            g.setFont(font);
            g.setColor(COLOR);
            FontMetrics fm = g.getFontMetrics();
            int x = (src.getWidth() - fm.stringWidth(TEXT)) / 2;
            g.drawString(TEXT, x, BASELINE_Y);
            System.out.println("drawn at x=" + x + " baseline=" + BASELINE_Y
                    + " width=" + fm.stringWidth(TEXT) + " font=" + font.getFontName());
        } finally {
            g.dispose();
        }
        ImageIO.write(src, "png", new File(args[1]));
        System.out.println("wrote " + args[1]);
    }
}
