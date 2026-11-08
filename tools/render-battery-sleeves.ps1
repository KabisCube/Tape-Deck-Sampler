# Draws the battery label artwork into app/src/main/res/drawable-nodpi/battery_*.png and a preview sheet of all brands.
# The app adds the metal ends and cylinder shading at runtime, so these PNGs are flat labels with the + end on the right.
param([string]$ResDir = "$PSScriptRoot/../app/src/main/res/drawable-nodpi", [string]$Sheet = "$PSScriptRoot/../dist/battery-brands.png")
Add-Type -ReferencedAssemblies System.Drawing -TypeDefinition @"
using System; using System.Drawing; using System.Drawing.Drawing2D; using System.Drawing.Text; using System.Drawing.Imaging;
public static class Sleeves {
  public static string[] Files = {"tapecell","claptron","loopjuice","hiss","rewinder","wowflutter","bside","spoolboy","padpower","kabiscell","doubletime","voltowl"};
  const float L = -113, R = 113, H = 54.2f, S = 3f;
  static Graphics g;
  static Color C(uint v) { return Color.FromArgb(unchecked((int)v)); }
  static Color A(int a, Color c) { return Color.FromArgb(a, c); }
  static Color Tint(Color c, Color t, float a) { return Color.FromArgb(255, (int)Math.Round(c.R*(1-a)+t.R*a), (int)Math.Round(c.G*(1-a)+t.G*a), (int)Math.Round(c.B*(1-a)+t.B*a)); }
  static void Rect(float l, float t, float r, float b, Color c) { using (var br = new SolidBrush(c)) g.FillRectangle(br, l, t, r - l, b - t); }
  static void Poly(Color c, params float[] p) { var pts = new PointF[p.Length / 2]; for (int i = 0; i < pts.Length; i++) pts[i] = new PointF(p[i*2], p[i*2+1]); using (var br = new SolidBrush(c)) g.FillPolygon(br, pts); }
  static void Circle(float x, float y, float r, Color c) { using (var br = new SolidBrush(c)) g.FillEllipse(br, x - r, y - r, r * 2, r * 2); }
  static void Ring(float x, float y, float r, Color c, float w) { using (var p = new Pen(c, w)) g.DrawEllipse(p, x - r, y - r, r * 2, r * 2); }
  static void Line(float x1, float y1, float x2, float y2, Color c, float w) { using (var p = new Pen(c, w)) { p.StartCap = LineCap.Round; p.EndCap = LineCap.Round; g.DrawLine(p, x1, y1, x2, y2); } }
  static void Lines(Color c, float w, params float[] p) { var pts = new PointF[p.Length / 2]; for (int i = 0; i < pts.Length; i++) pts[i] = new PointF(p[i*2], p[i*2+1]); using (var pen = new Pen(c, w)) { pen.LineJoin = LineJoin.Round; pen.StartCap = LineCap.Round; pen.EndCap = LineCap.Round; g.DrawLines(pen, pts); } }
  static void Wave(Func<float, float> y, Color c, float w) { var pts = new System.Collections.Generic.List<PointF>(); for (float x = L - 2; x <= R + 2; x += 2) pts.Add(new PointF(x, y(x))); using (var pen = new Pen(c, w)) g.DrawLines(pen, pts.ToArray()); }
  static void Text(string s, string font, FontStyle style, float size, Color c, float cx, float baseline, float skew) {
    using (var f = new Font(font, size, style, GraphicsUnit.Pixel)) {
      var fmt = StringFormat.GenericTypographic;
      var w = g.MeasureString(s, f, PointF.Empty, fmt).Width;
      float ascent = size * f.FontFamily.GetCellAscent(style) / f.FontFamily.GetEmHeight(style);
      var st = g.Save();
      g.TranslateTransform(cx, baseline);
      if (skew != 0) g.MultiplyTransform(new Matrix(1, 0, skew, 1, 0, 0));
      using (var br = new SolidBrush(c)) g.DrawString(s, f, br, -w / 2, -ascent, fmt);
      g.Restore(st);
    }
  }
  static void Wobbly(string s, string font, FontStyle style, float size, Color c, float cx, float baseline) {
    using (var f = new Font(font, size, style, GraphicsUnit.Pixel)) {
      var fmt = StringFormat.GenericTypographic;
      float total = g.MeasureString(s, f, PointF.Empty, fmt).Width;
      float ascent = size * f.FontFamily.GetCellAscent(style) / f.FontFamily.GetEmHeight(style);
      float x = cx - total / 2;
      for (int i = 0; i < s.Length; i++) {
        string ch = s.Substring(i, 1);
        float w = g.MeasureString(ch, f, PointF.Empty, fmt).Width;
        var st = g.Save();
        g.TranslateTransform(x + w / 2, baseline + (float)Math.Sin(i * 1.15) * 2.6f);
        g.RotateTransform((float)Math.Sin(i * 0.9 + 1) * 6);
        using (var br = new SolidBrush(c)) g.DrawString(ch, f, br, -w / 2, -ascent, fmt);
        g.Restore(st);
        x += w;
      }
    }
  }
  static void Cube(float x, float y, float size, Color top, Color left, Color right) {
    float h = size * 0.5f;
    Poly(top, x, y - size, x + size, y - size + h, x, y - size + 2 * h, x - size, y - size + h);
    Poly(left, x - size, y - size + h, x, y - size + 2 * h, x, y + size, x - size, y + size - h);
    Poly(right, x + size, y - size + h, x, y - size + 2 * h, x, y + size, x + size, y + size - h);
  }
  static void Tag(string s, Color c, float cx, float baseline) { Text("AA 1.5V " + (char)0xB7 + " " + s, "Consolas", FontStyle.Regular, 6.2f, c, cx, baseline, 0); }

  static void Design(int i) {
    Color white = Color.White;
    switch (i) {
      case 0: {
        Color red = C(0xffe8202d), yellow = C(0xffffd23f), dark = C(0xff7a0d14);
        Rect(L, -H, R, H, red); Rect(41, -H, R, H, yellow); Rect(34, -H, 37.5f, H, white); Rect(L, -H, L + 5, H, yellow);
        using (var p = new Pen(white, 1.6f)) { var path = new GraphicsPath(); path.AddRectangle(new RectangleF(-103, -9, 26, 17)); g.DrawPath(p, path); }
        Ring(-95, -2, 3.3f, white, 1.4f); Ring(-85, -2, 3.3f, white, 1.4f); Line(-98, 5, -80, 5, white, 1.2f);
        Text("TAPECELL", "Segoe UI Black", FontStyle.Regular, 17, A(90, dark), -21, 5, 0);
        Text("TAPECELL", "Segoe UI Black", FontStyle.Regular, 17, white, -22, 4, 0); Tag("20 SEC GUARANTEED", A(220, white), -22, 15);
        Text("+", "Segoe UI Black", FontStyle.Regular, 24, red, 77, 8, 0); break; }
      case 1: {
        Color navy = C(0xff22305e), yellow = C(0xfff5c842);
        Rect(L, -H, R, H, navy); Poly(yellow, 28, -H, 50, -H, 30, H, 8, H); Poly(yellow, 56, -H, 62, -H, 42, H, 36, H);
        Circle(86, 0, 19, yellow);
        for (int k = 0; k < 3; k++) { using (var p = new Pen(navy, 2.2f)) { float rr = 5 + k * 5; g.DrawArc(p, 78 - rr, -rr, rr * 2, rr * 2, -50, 100); } }
        Circle(78, 0, 2.6f, navy);
        Text("CLAPTRON", "Segoe UI Black", FontStyle.Regular, 18, yellow, -48, 4, -0.25f); Tag("SYNCS ON CLAP", A(190, yellow), -48, 15);
        Text("+", "Segoe UI Black", FontStyle.Regular, 13, yellow, 106, -20, 0); break; }
      case 2: {
        Color lime = C(0xffc6e86b), dark = C(0xff1f2a10);
        Rect(L, -H, R, H, lime);
        Poly(Tint(lime, white, 0.45f), -70, -H, 4, -H, -14, -3, 34, -3, -46, H, -24, 5, -78, 5);
        using (var p = new Pen(dark, 1.4f)) { var path = new GraphicsPath(); path.AddRectangle(new RectangleF(70, -16, 28, 32)); g.DrawPath(p, path); }
        Line(70, 0, 98, 0, dark, 1f);
        Text("AA", "Segoe UI Black", FontStyle.Regular, 10, dark, 84, -4, 0); Text("++", "Segoe UI Black", FontStyle.Regular, 10, dark, 84, 11, 0);
        Text("LOOPJUICE", "Impact", FontStyle.Regular, 24, dark, -24, 7, -0.3f); Tag("NEVER ENDS*", A(200, dark), -24, 19); break; }
      case 3: {
        Color silver = C(0xffc9cdd3), orange = C(0xfff08a3c), ink = C(0xff26313b);
        Rect(L, -H, R, H, silver);
        var rnd = new Random(7); for (int k = 0; k < 26; k++) { float y = -H + (float)rnd.NextDouble() * 2 * H; Line(L, y, R, y, rnd.Next(2) == 0 ? A(40, white) : A(22, Color.Black), 0.6f); }
        Wave(x => 20 + 4.5f * (float)Math.Sin(x / 7.0), orange, 3.2f);
        Wave(x => 27 + 3f * (float)Math.Sin(x / 5.0 + 1), A(160, orange), 1.4f);
        Circle(88, -8, 14, orange); Text("+", "Segoe UI Black", FontStyle.Regular, 20, white, 88, -1, 0);
        Text("HISS+", "Bahnschrift", FontStyle.Bold, 24, ink, -32, 3, -0.15f); Tag("EXTRA WARMTH", A(200, ink), -32, 13); break; }
      case 4: {
        Color purple = C(0xff3a1c71), yellow = C(0xffffcf3a);
        Rect(L, -H, R, H, purple);
        Color[] stripes = { C(0xff21c7c2), C(0xffff3d7f), C(0xffff7b2c) };
        for (int k = 2; k >= 0; k--) { float dx = -6 * (k + 1); Poly(stripes[k], 34 + dx - 6, -H, 34 + dx, -H, 6 + dx, H, 6 + dx - 6, H); }
        Poly(yellow, 34, -H, R, -H, R, H, 6, H);
        Poly(purple, 72, -14, 72, 2, 60, -6); Poly(purple, 86, -14, 86, 2, 74, -6); Rect(56, -14, 59, 2, purple);
        Text("2X", "Consolas", FontStyle.Bold, 8, purple, 72, 12, 0);
        for (int k = 0; k < 9; k++) Rect(-104 + k * 9, 24, -99 + k * 9, 26, A(90, white));
        Text("REWINDER", "Trebuchet MS", FontStyle.Bold, 18, A(120, Color.Black), -53, 5, -0.12f);
        Text("REWINDER", "Trebuchet MS", FontStyle.Bold, 18, white, -54, 4, -0.12f); Tag("BE KIND", A(210, yellow), -54, 15);
        Text("+", "Segoe UI Black", FontStyle.Regular, 18, purple, 100, 24, 0); break; }
      case 5: {
        Color coral = C(0xffff8f7a), teal = C(0xff1f8f8a), ink = C(0xff4a1a12);
        Rect(L, -H, R, H, coral);
        var path = new GraphicsPath(); var pts = new System.Collections.Generic.List<PointF>();
        for (float x = L - 2; x <= R + 2; x += 2) pts.Add(new PointF(x, 14 + 4 * (float)Math.Sin(x / 11.0)));
        pts.Add(new PointF(R + 2, H + 1)); pts.Add(new PointF(L - 2, H + 1)); path.AddPolygon(pts.ToArray());
        using (var br = new SolidBrush(teal)) g.FillPath(br, path);
        Wave(x => -25 + 2.5f * (float)Math.Sin(x / 6.0), teal, 2f);
        Wobbly("WOW&FLUTTER", "Segoe UI Black", FontStyle.Regular, 15, ink, -14, 3);
        Tag("SLIGHTLY WOBBLY", A(230, white), -14, 27);
        Text("+", "Segoe UI Black", FontStyle.Regular, 18, ink, 98, 0, 0); break; }
      case 6: {
        Color black = C(0xff1d1d22), mint = C(0xff9fe3c2);
        Rect(L, -H, R, H, black);
        Circle(-80, 0, 29, C(0xff2c2c33)); for (int k = 0; k < 5; k++) Ring(-80, 0, 27 - k * 3.2f, A(36, white), 0.7f);
        Circle(-80, 0, 11, mint); Circle(-80, 0, 1.8f, black);
        Text("B", "Segoe UI Black", FontStyle.Regular, 9, black, -80, -2.5f, 0);
        Rect(R - 28, -H, R - 24, H, mint); Rect(R - 18, -H, R - 14, H, mint);
        Text("B-SIDE", "Georgia", FontStyle.Bold | FontStyle.Italic, 21, mint, 6, 4, 0); Tag("DEEP CUTS ONLY", A(190, mint), 6, 15);
        Text("+", "Segoe UI Black", FontStyle.Regular, 18, mint, 70, 0, 0); break; }
      case 7: {
        Color sun = C(0xffffc21a), orange = C(0xffe8740c), blue = C(0xff1f4fd6), navy = C(0xff1c2b6b), cream = C(0xfffff3d6);
        Rect(L, -H, R, H, sun);
        for (float y = -H + 4; y < H; y += 6) Line(L, y, R - 46, y, A(70, orange), 1.1f);
        Rect(R - 46, -H, R, H, blue); Rect(R - 50, -H, R - 47, H, navy);
        Ring(R - 23, 0, 13, cream, 2f); Circle(R - 23, 0, 4, cream);
        for (int k = 0; k < 3; k++) { double a = k * Math.PI * 2 / 3; Line(R - 23, 0, R - 23 + 12 * (float)Math.Cos(a), 12 * (float)Math.Sin(a), cream, 1.6f); }
        Text("SPOOLBOY", "Georgia", FontStyle.Bold, 18, navy, -36, 4, 0); Tag("FULLY WOUND", A(210, navy), -36, 15);
        Text("+", "Segoe UI Black", FontStyle.Regular, 18, navy, R - 62, 7, 0); break; }
      case 8: {
        Color orange = C(0xffeb5231), cream = C(0xfff6efe0);
        Rect(L, -H, R, H, orange); Rect(R - 6, -H, R, H, cream); Rect(L, -H, L + 4, H, cream);
        int[] lit = {0, 5, 6, 10, 15, 3, 12};
        for (int k = 0; k < 16; k++) { float x = 52 + (k % 4) * 12, y = -23 + (k / 4) * 12; Rect(x, y, x + 9, y + 9, Array.IndexOf(lit, k) >= 0 ? cream : A(80, cream)); }
        Text("PADPOWER", "Arial Black", FontStyle.Regular, 15, cream, -40, 4, -0.2f); Tag("16 PADS / 5 HRS", A(220, cream), -40, 15);
        Text("+", "Segoe UI Black", FontStyle.Regular, 18, cream, 32, 7, 0); break; }
      case 9: {
        Color violet = C(0xff5b2bff);
        Rect(L, -H, R, H, violet);
        Cube(-100, -20, 4, C(0xff8f6bff), C(0xff6a43f0), C(0xff4c26c9));
        Cube(-92, 22, 3, C(0xff8f6bff), C(0xff6a43f0), C(0xff4c26c9));
        Cube(20, -24, 3, C(0xff8f6bff), C(0xff6a43f0), C(0xff4c26c9));
        Cube(64, 8, 11, C(0xffffd84a), C(0xfff2a900), C(0xffc98500));
        Cube(86, 8, 11, C(0xffff5fa8), C(0xffe0307f), C(0xffb01e60));
        Cube(75, -11, 11, C(0xff4ee6ff), C(0xff14b8e0), C(0xff0a86a8));
        Cube(-96, 0, 7, C(0xffffd84a), C(0xfff2a900), C(0xffc98500));
        Text("KABISCELL", "Segoe UI Black", FontStyle.Regular, 17, white, -26, 4, 0); Tag("CUBE POWERED", A(220, C(0xff4ee6ff)), -26, 15);
        Text("+", "Segoe UI Black", FontStyle.Regular, 14, C(0xffffd84a), 104, -20, 0); break; }
      case 10: {
        Color pink = C(0xffff2d8a), black = C(0xff141016);
        Rect(L, -H, R, H, pink);
        Poly(black, 62, -H, R, -H, R, H, 42, H);
        Poly(black, 46, -H, 54, -H, 34, H, 26, H); Poly(black, 34, -H, 38, -H, 18, H, 14, H);
        Lines(white, 1.6f, 78, 15, 98, 15, 92, -15, 84, -15, 78, 15);
        Line(88, 9, 96, -13, pink, 2.4f); Circle(93, -5, 3, pink);
        for (int k = 0; k < 3; k++) Line(-110, -10 + k * 7, -99 + k * 3, -10 + k * 7, A(220, white), 1.6f);
        Text("DOUBLETIME", "Segoe UI Black", FontStyle.Regular, 16, white, -30, 4, -0.3f); Tag("240 BPM READY", A(230, white), -30, 15);
        Text("+", "Segoe UI Black", FontStyle.Regular, 13, pink, 106, -20, 0); break; }
      case 11: {
        Color night = C(0xff15302e), yellow = C(0xfff2d14b), white2 = C(0xfff4fffd);
        Rect(L, -H, R, H, night); Rect(R - 28, -H, R, H, yellow);
        Text("+", "Segoe UI Black", FontStyle.Regular, 18, night, R - 14, 7, 0);
        Circle(52, -7, 9, yellow); Circle(72, -7, 9, yellow); Circle(53, -6, 3.8f, night); Circle(71, -6, 3.8f, night);
        Poly(C(0xfff08a3c), 59, 3, 65, 3, 62, 9);
        Lines(yellow, 1.6f, 40, -19, 46, -14); Lines(yellow, 1.6f, 84, -19, 78, -14);
        using (var br = new SolidBrush(C(0xff0a1716))) g.FillRectangle(br, -72, 10, 60, 12);
        using (var p = new Pen(yellow, 1.2f)) g.DrawRectangle(p, -72, 10, 60, 12);
        Rect(-70, 12, -70 + 38, 20, yellow); for (int k = 1; k < 6; k++) Line(-72 + k * 10f, 20, -72 + k * 10f, 22, A(160, yellow), 0.8f);
        Text("TEST", "Consolas", FontStyle.Bold, 6, yellow, -21, 18.5f, 0);
        Text("VOLTOWL", "Bahnschrift", FontStyle.Bold, 21, white2, -28, 2, 0); Tag("NIGHT SHIFT", A(200, white2), 32, 19); break; }
    }
  }

  static Bitmap Sleeve(int i) {
    var bmp = new Bitmap((int)((R - L) * S), (int)(2 * H * S), PixelFormat.Format32bppArgb);
    g = Graphics.FromImage(bmp); g.SmoothingMode = SmoothingMode.AntiAlias; g.TextRenderingHint = TextRenderingHint.AntiAlias;
    g.ScaleTransform(S, S); g.TranslateTransform(-L, H);
    Design(i); g.Dispose();
    return bmp;
  }

  static Brush Grad(float top, float bottom, Color[] cols, float[] pos) {
    var b = new LinearGradientBrush(new PointF(0, top - 0.01f), new PointF(0, bottom + 0.01f), cols[0], cols[cols.Length - 1]);
    var blend = new ColorBlend(); blend.Colors = cols; blend.Positions = pos; b.InterpolationColors = blend; return b;
  }

  public static void Run(string resDir, string sheet) {
    var sleeves = new Bitmap[12];
    for (int i = 0; i < 12; i++) { sleeves[i] = Sleeve(i); sleeves[i].Save(System.IO.Path.Combine(resDir, "battery_" + Files[i] + ".png"), ImageFormat.Png); }
    float s = 3f; int cols = 2; float cellW = 280, cellH = 100, top = 46;
    var outBmp = new Bitmap((int)(cols * cellW * s), (int)((6 * cellH + top + 10) * s));
    var o = Graphics.FromImage(outBmp); o.SmoothingMode = SmoothingMode.AntiAlias; o.TextRenderingHint = TextRenderingHint.AntiAlias; o.InterpolationMode = InterpolationMode.HighQualityBicubic;
    o.Clear(Color.FromArgb(52, 53, 50)); o.ScaleTransform(s, s);
    using (var f = new Font("Segoe UI", 20f, FontStyle.Bold, GraphicsUnit.Pixel)) { var w = o.MeasureString("TAPE DECK SAMPLER " + (char)0xB7 + " BATTERY BRANDS", f).Width; using (var br = new SolidBrush(Color.FromArgb(220, 218, 208))) o.DrawString("TAPE DECK SAMPLER " + (char)0xB7 + " BATTERY BRANDS", f, br, cols * cellW / 2 - w / 2, 12); }
    Color[] metal = { C(0xff8a8e95), C(0xfff2f3f5), C(0xffc3c7cd), C(0xff6f737a) }; float[] mp = { 0f, .25f, .55f, 1f };
    for (int i = 0; i < 12; i++) {
      float cx = (i % cols) * cellW + cellW / 2, cy = top + (i / cols) * cellH + cellH / 2, r = 34.5f, half = 119;
      var st = o.Save(); o.TranslateTransform(cx, cy);
      using (var sh = new SolidBrush(Color.FromArgb(0x50, 0, 0, 0))) o.FillRectangle(sh, -114, -27, 236, 70);
      o.FillRectangle(Grad(-r * .34f, r * .34f, metal, mp), half - 5, -r * .34f, 5, r * .68f);
      o.FillRectangle(Grad(-r, r, metal, mp), -half, -r + .5f, 3, 2 * r - 1);
      o.FillRectangle(Grad(-r, r, metal, mp), half - 9, -r + 1, 4, 2 * r - 2);
      int strips = 60;
      for (int k = 0; k < strips; k++) {
        float y0 = -r + 2 * r * k / strips, y1 = y0 + 2 * r / strips;
        float v0 = (float)((Math.Asin(Math.Max(-1, Math.Min(1, y0 / r))) + Math.PI / 2) / Math.PI), v1 = (float)((Math.Asin(Math.Max(-1, Math.Min(1, y1 / r))) + Math.PI / 2) / Math.PI);
        o.DrawImage(sleeves[i], new RectangleF(-half + 3, y0, 226, y1 - y0 + 0.4f), new RectangleF(0, v0 * sleeves[i].Height, sleeves[i].Width, (v1 - v0) * sleeves[i].Height), GraphicsUnit.Pixel);
      }
      o.FillRectangle(Grad(-r, r, new[] { Color.FromArgb(0x60, 0, 0, 0), Color.FromArgb(0x10, 255, 255, 255), Color.FromArgb(0x80, 255, 255, 255), Color.FromArgb(0x18, 255, 255, 255), Color.FromArgb(0, 255, 255, 255), Color.FromArgb(0x38, 0, 0, 0), Color.FromArgb(0x90, 0, 0, 0) }, new[] { 0f, .12f, .22f, .34f, .5f, .75f, 1f }), -half, -r, 2 * half - 5, 2 * r);
      using (var p = new Pen(Color.FromArgb(0x90, 255, 255, 255), 2.2f)) o.DrawLine(p, -half + 6, -r * .56f, half - 12, -r * .56f);
      o.Restore(st);
    }
    outBmp.Save(sheet, ImageFormat.Png);
  }
}
"@
[Sleeves]::Run($ResDir, $Sheet)
