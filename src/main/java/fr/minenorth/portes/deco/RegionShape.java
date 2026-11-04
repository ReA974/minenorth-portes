package fr.minenorth.portes.deco;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Forme d'une région WorldGuard (cuboïde ou polygone 2D avec hauteur), lue dans regions.yml. Sans Minecraft : testable.
 * Coordonnées de blocs, bornes incluses (comme WorldGuard).
 */
public record RegionShape(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, int[] polyX, int[] polyZ) {

    public static RegionShape box(int x1, int y1, int z1, int x2, int y2, int z2) {
        return new RegionShape(Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2), Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2), null, null);
    }

    public boolean contains(int x, int y, int z) {
        if (x < minX || x > maxX || y < minY || y > maxY || z < minZ || z > maxZ) return false;
        if (polyX == null) return true;
        return insidePolygon(x, z);
    }

    /** Point dans le polygone (bords compris), comme ProtectedPolygonalRegion.contains de WorldGuard. */
    private boolean insidePolygon(int x, int z) {
        int n = polyX.length;
        boolean inside = false;
        int xOld = polyX[n - 1], zOld = polyZ[n - 1];
        for (int i = 0; i < n; i++) {
            int xNew = polyX[i], zNew = polyZ[i];
            if (xNew == x && zNew == z) return true;
            int x1, z1, x2, z2;
            if (xNew > xOld) { x1 = xOld; x2 = xNew; z1 = zOld; z2 = zNew; } else { x1 = xNew; x2 = xOld; z1 = zNew; z2 = zOld; }
            if (x1 <= x && x <= x2) {
                long crossproduct = ((long) z - (long) z1) * (long) (x2 - x1) - ((long) z2 - (long) z1) * (long) (x - x1);
                if (crossproduct == 0) {
                    if ((z1 <= z) == (z <= z2)) return true;
                } else if (crossproduct < 0 && (x1 != x)) {
                    inside = !inside;
                }
            }
            xOld = xNew;
            zOld = zNew;
        }
        return inside;
    }

    public long volume() { return (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1); }

    // ------------------------------------------------------------------ lecture de regions.yml

    private static final Pattern NUM_X = Pattern.compile("\\bx:\\s*(-?[0-9.]+)"), NUM_Y = Pattern.compile("\\by:\\s*(-?[0-9.]+)"),
            NUM_Z = Pattern.compile("\\bz:\\s*(-?[0-9.]+)");

    private static int indent(String l) {
        int i = 0;
        while (i < l.length() && l.charAt(i) == ' ') i++;
        return i;
    }

    private static boolean blank(String l) { String t = l.trim(); return t.isEmpty() || t.startsWith("#"); }

    /** Texte de la clé « key: » (reste de la ligne + lignes plus indentées qui suivent), ou null. */
    private static String section(List<String> lines, String key) {
        for (int i = 0; i < lines.size(); i++) {
            String t = lines.get(i).trim();
            if (!t.startsWith(key + ":")) continue;
            int ind = indent(lines.get(i));
            StringBuilder b = new StringBuilder(t.substring(key.length() + 1)).append('\n');
            for (int j = i + 1; j < lines.size() && (blank(lines.get(j)) || indent(lines.get(j)) > ind
                    || (indent(lines.get(j)) == ind && lines.get(j).trim().startsWith("-"))); j++) b.append(lines.get(j)).append('\n');
            return b.toString();
        }
        return null;
    }

    private static int num(Pattern p, String text) {
        Matcher m = p.matcher(text);
        if (!m.find()) throw new IllegalArgumentException("coordonnée manquante");
        return (int) Math.floor(Double.parseDouble(m.group(1)));
    }

    private static int scalar(List<String> lines, String key) {
        String s = section(lines, key);
        if (s == null) throw new IllegalArgumentException(key + " manquant");
        return (int) Math.floor(Double.parseDouble(s.trim().split("\\s")[0]));
    }

    private static RegionShape region(List<String> lines) {
        String type = section(lines, "type");
        type = type == null ? "cuboid" : type.trim().toLowerCase(Locale.ROOT);
        if (type.startsWith("cuboid")) {
            String min = section(lines, "min"), max = section(lines, "max");
            if (min == null || max == null) return null;
            return box(num(NUM_X, min), num(NUM_Y, min), num(NUM_Z, min), num(NUM_X, max), num(NUM_Y, max), num(NUM_Z, max));
        }
        if (type.startsWith("poly2d")) {
            String pts = section(lines, "points");
            if (pts == null) return null;
            List<Integer> xs = new ArrayList<>(), zs = new ArrayList<>();
            Matcher mx = NUM_X.matcher(pts), mz = NUM_Z.matcher(pts);
            while (mx.find() && mz.find()) {
                xs.add((int) Math.floor(Double.parseDouble(mx.group(1))));
                zs.add((int) Math.floor(Double.parseDouble(mz.group(1))));
            }
            if (xs.size() < 3) return null;
            int[] px = xs.stream().mapToInt(Integer::intValue).toArray(), pz = zs.stream().mapToInt(Integer::intValue).toArray();
            int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            for (int i = 0; i < px.length; i++) {
                minX = Math.min(minX, px[i]); maxX = Math.max(maxX, px[i]);
                minZ = Math.min(minZ, pz[i]); maxZ = Math.max(maxZ, pz[i]);
            }
            return new RegionShape(minX, scalar(lines, "min-y"), minZ, maxX, scalar(lines, "max-y"), maxZ, px, pz);
        }
        return null;   // global ou type inconnu
    }

    /** Régions d'un fichier regions.yml (identifiants en minuscules). Une région illisible est ignorée. */
    public static Map<String, RegionShape> parse(String yml) {
        Map<String, RegionShape> out = new HashMap<>();
        String[] all = yml.replace("\r", "").split("\n");
        int start = -1;
        for (int i = 0; i < all.length; i++) if (all[i].trim().equals("regions:") && indent(all[i]) == 0) { start = i + 1; break; }
        if (start < 0) return out;
        int regionIndent = -1;
        String id = null;
        List<String> body = new ArrayList<>();
        for (int i = start; i <= all.length; i++) {
            String l = i < all.length ? all[i] : "";
            boolean end = i == all.length || (!blank(l) && indent(l) == 0);
            if (!end && blank(l)) continue;
            int ind = end ? 0 : indent(l);
            if (!end && regionIndent < 0) regionIndent = ind;
            if (end || ind == regionIndent) {
                if (id != null) {
                    try {
                        RegionShape r = region(body);
                        if (r != null) out.put(id, r);
                    } catch (RuntimeException ignored) {
                        // région illisible : ignorée
                    }
                }
                if (end) break;
                String t = l.trim();
                int c = t.indexOf(':');
                id = c < 0 ? null : t.substring(0, c).replace("'", "").replace("\"", "").trim().toLowerCase(Locale.ROOT);
                body = new ArrayList<>();
            } else {
                body.add(l);
            }
        }
        return out;
    }
}
