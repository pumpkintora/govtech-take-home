package com.chat.chat;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatRepositoryTest {

    @Test
    void unsignedUuidComparisonMatchesPostgresOrderingAcrossSignBit() {
        // Java's UUID.compareTo() treats 8... as negative (< 7...), whereas PostgreSQL compares
        // UUIDs as unsigned 128-bit values (7... < 8...). Verify our helper matches PostgreSQL.
        UUID lowHex = UUID.fromString("7fffffff-ffff-4fff-8fff-ffffffffffff");
        UUID highHex = UUID.fromString("80000000-0000-4000-8000-000000000000");

        assertTrue(ChatRepository.compareUnsigned(lowHex, highHex) < 0);
        assertTrue(ChatRepository.compareUnsigned(highHex, lowHex) > 0);

        UUID sameMsbLowLsb = UUID.fromString("12345678-1234-4234-7fff-ffffffffffff");
        UUID sameMsbHighLsb = UUID.fromString("12345678-1234-4234-8000-000000000000");
        assertTrue(ChatRepository.compareUnsigned(sameMsbLowLsb, sameMsbHighLsb) < 0);
    }
}
