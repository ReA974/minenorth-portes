package fr.minenorth.portes.deco;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RegionShapeTest {
    private static final String YML = """
            regions:
                maison_12:
                    min: {x: -10.0, y: 60.0, z: 5.0}
                    max: {x: 4.0, y: 80.0, z: 20.0}
                    members: {}
                    flags: {}
                    owners: {}
                    type: cuboid
                    priority: 0
                appart_b:
                    min-y: 64
                    max-y: 70
                    points:
                    - {x: 0.0, z: 0.0}
                    - {x: 10.0, z: 0.0}
                    - {x: 10.0, z: 10.0}
                    - {x: 0.0, z: 10.0}
                    type: poly2d
                bloc:
                    min:
                        x: 1
                        y: 2
                        z: 3
                    max:
                        x: 5
                        y: 6
                        z: 7
                    type: cuboid
                __global__:
                    type: global
            """;

    @Test void readsCuboidPolygonAndBlockStyle() {
        Map<String, RegionShape> r = RegionShape.parse(YML);
        assertEquals(3, r.size());
        RegionShape m = r.get("maison_12");
        assertTrue(m.contains(-10, 60, 5));
        assertTrue(m.contains(4, 80, 20));
        assertFalse(m.contains(5, 70, 10));
        RegionShape p = r.get("appart_b");
        assertTrue(p.contains(5, 65, 5));
        assertTrue(p.contains(10, 64, 10));
        assertFalse(p.contains(11, 65, 5));
        assertFalse(p.contains(5, 71, 5));
        assertTrue(r.get("bloc").contains(3, 4, 5));
        assertFalse(r.get("bloc").contains(0, 4, 5));
    }

    @Test void boxVolume() { assertEquals(8, RegionShape.box(0, 0, 0, 1, 1, 1).volume()); }
}
