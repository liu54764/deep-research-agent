package com.dra.common.id;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdsTest {

    @Test
    void generatesValidUlid() {
        String id = Ids.ulid();
        assertEquals(26, id.length());
        assertTrue(Ids.isUlid(id));
    }

    @Test
    void idsAreUnique() {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            assertTrue(ids.add(Ids.ulid()));
        }
    }

    @Test
    void rejectsInvalidValues() {
        assertFalse(Ids.isUlid(null));
        assertFalse(Ids.isUlid("too-short"));
        assertFalse(Ids.isUlid("01ARZ3NDEKTSV4RRFFQ69G5FAI")); // contains I
    }
}
