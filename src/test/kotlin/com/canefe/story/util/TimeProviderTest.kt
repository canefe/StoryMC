package com.canefe.story.util

import com.canefe.story.Story
import io.mockk.*
import me.casperge.realisticseasons.api.SeasonsAPI
import me.casperge.realisticseasons.calendar.Date
import me.casperge.realisticseasons.season.Season
import org.bukkit.Bukkit
import org.bukkit.World
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class TimeProviderTest {
    @Nested
    inner class FallbackTimeProviderTest {
        private lateinit var provider: FallbackTimeProvider

        @BeforeEach
        fun setUp() {
            provider = FallbackTimeProvider()
        }

        @Test
        fun `getCurrentGameTime returns non-negative value`() {
            assertTrue(provider.getCurrentGameTime() >= 0)
        }

        @Test
        fun `getCurrentGameTime increases over time`() {
            val time1 = provider.getCurrentGameTime()
            Thread.sleep(50)
            val time2 = provider.getCurrentGameTime()
            assertTrue(time2 >= time1)
        }

        @Test
        fun `getFormattedDate returns fallback string`() {
            assertEquals("Day 1", provider.getFormattedDate())
        }

        @Test
        fun `getHours returns value in valid range`() {
            assertTrue(provider.getHours() in 0..23)
        }

        @Test
        fun `getMinutes returns value in valid range`() {
            assertTrue(provider.getMinutes() in 0..59)
        }

        @Test
        fun `getSeason returns Spring`() {
            assertEquals("Spring", provider.getSeason())
        }
    }

    @Nested
    inner class RealisticSeasonsTimeProviderTest {
        private lateinit var provider: RealisticSeasonsTimeProvider
        private lateinit var mockWorld: World
        private lateinit var mockApi: SeasonsAPI

        @BeforeEach
        fun setUp() {
            mockWorld = mockk<World>(relaxed = true)
            mockApi = mockk<SeasonsAPI>(relaxed = true)

            mockkStatic(Bukkit::class)
            every { Bukkit.getWorld("world") } returns mockWorld
            every { Bukkit.getWorlds() } returns listOf(mockWorld)
            every { Bukkit.getLogger() } returns mockk(relaxed = true)

            mockkStatic(SeasonsAPI::class)
            every { SeasonsAPI.getInstance() } returns mockApi
        }

        @AfterEach
        fun tearDown() {
            unmockkAll()
        }

        @Test
        fun `init succeeds when SeasonsAPI is available`() {
            provider = RealisticSeasonsTimeProvider()
            assertNotNull(provider)
        }

        @Test
        fun `init throws when SeasonsAPI returns null`() {
            every { SeasonsAPI.getInstance() } returns null
            assertThrows(IllegalStateException::class.java) {
                RealisticSeasonsTimeProvider()
            }
        }

        @Test
        fun `getCurrentGameTime calculates correctly from date`() {
            val date = Date(15, 3, 2)
            every { mockApi.getDate(mockWorld) } returns date
            every { mockApi.getHours(mockWorld) } returns 10
            every { mockApi.getMinutes(mockWorld) } returns 30

            provider = RealisticSeasonsTimeProvider()
            val time = provider.getCurrentGameTime()

            // year=2, month=3, day=15, hours=10, minutes=30
            // dayOfYear = (31 + 28) + 15 = 74
            // expected = 2 * 525600 + 74 * 1440 + 10 * 60 + 30
            val expected = (2 * 525600 + 74 * 1440 + 10 * 60 + 30).toLong()
            assertEquals(expected, time)
        }

        @Test
        fun `getCurrentGameTime handles null date with fallback`() {
            every { mockApi.getDate(mockWorld) } returns null

            provider = RealisticSeasonsTimeProvider()
            val time = provider.getCurrentGameTime()
            assertTrue(time > 0, "Should fallback to system time")
        }

        @Test
        fun `getFormattedDate delegates to SeasonsAPI`() {
            val date = mockk<Date>()
            every { date.toString(true) } returns "15/3/2"
            every { mockApi.getDate(mockWorld) } returns date

            provider = RealisticSeasonsTimeProvider()
            assertEquals("15/3/2", provider.getFormattedDate())
        }

        @Test
        fun `getFormattedDate returns Unknown Date when null`() {
            every { mockApi.getDate(mockWorld) } returns null

            provider = RealisticSeasonsTimeProvider()
            assertEquals("Unknown Date", provider.getFormattedDate())
        }

        @Test
        fun `getHours delegates to SeasonsAPI`() {
            every { mockApi.getHours(mockWorld) } returns 14

            provider = RealisticSeasonsTimeProvider()
            assertEquals(14, provider.getHours())
        }

        @Test
        fun `getMinutes delegates to SeasonsAPI`() {
            every { mockApi.getMinutes(mockWorld) } returns 45

            provider = RealisticSeasonsTimeProvider()
            assertEquals(45, provider.getMinutes())
        }

        @Test
        fun `getSeason delegates to SeasonsAPI`() {
            every { mockApi.getSeason(mockWorld) } returns Season.WINTER

            provider = RealisticSeasonsTimeProvider()
            assertEquals("WINTER", provider.getSeason())
        }

        @Test
        fun `getSeason returns Unknown Season when null`() {
            every { mockApi.getSeason(mockWorld) } returns null

            provider = RealisticSeasonsTimeProvider()
            assertEquals("Unknown Season", provider.getSeason())
        }

        @Test
        fun `date fields accessed via getters not direct field access`() {
            // This test ensures we use getter methods (getMonth, getDay, getYear)
            // rather than direct field access, which would fail at runtime
            // when the real RealisticSeasons Date class has private fields
            val date = Date(10, 6, 3)
            every { mockApi.getDate(mockWorld) } returns date
            every { mockApi.getHours(mockWorld) } returns 0
            every { mockApi.getMinutes(mockWorld) } returns 0

            provider = RealisticSeasonsTimeProvider()
            // If this doesn't throw IllegalAccessError, getters are being used correctly
            assertDoesNotThrow { provider.getCurrentGameTime() }
        }
    }

    @Nested
    inner class TimeServiceTest {
        private lateinit var mockPlugin: Story
        private lateinit var mockProvider: TimeProvider

        @BeforeEach
        fun setUp() {
            mockPlugin = mockk<Story>(relaxed = true)
            mockProvider = mockk<TimeProvider>(relaxed = true)
        }

        @Test
        fun `delegates to provided TimeProvider`() {
            every { mockProvider.getCurrentGameTime() } returns 12345L
            every { mockProvider.getFormattedDate() } returns "Day 5"
            every { mockProvider.getHours() } returns 8
            every { mockProvider.getMinutes() } returns 30
            every { mockProvider.getSeason() } returns "SUMMER"

            val service = TimeService(mockPlugin, mockProvider)

            assertEquals(12345L, service.getCurrentGameTime())
            assertEquals("Day 5", service.getFormattedDate())
            assertEquals(8, service.getHours())
            assertEquals(30, service.getMinutes())
            assertEquals("SUMMER", service.getSeason())
        }

        @Test
        fun `calculateMemoryDecay returns lower value over time`() {
            every { mockProvider.getCurrentGameTime() } returns 2880L // 2 days in minutes

            val service = TimeService(mockPlugin, mockProvider)
            val decay =
                service.calculateMemoryDecay(
                    createdAt = 0L,
                    decayRate = 0.5,
                    currentPower = 1.0,
                )

            assertTrue(decay > 0.0, "Decay should be positive")
            assertTrue(decay < 1.0, "Decay should be less than initial power")
        }

        @Test
        fun `calculateMemoryDecay returns full power when no time elapsed`() {
            every { mockProvider.getCurrentGameTime() } returns 100L

            val service = TimeService(mockPlugin, mockProvider)
            val decay =
                service.calculateMemoryDecay(
                    createdAt = 100L,
                    decayRate = 0.5,
                    currentPower = 1.0,
                )

            assertEquals(1.0, decay, 0.001)
        }

        @Test
        fun `calculateMemoryDecay higher rate means faster decay`() {
            every { mockProvider.getCurrentGameTime() } returns 1440L

            val service = TimeService(mockPlugin, mockProvider)
            val slowDecay =
                service.calculateMemoryDecay(
                    createdAt = 0L,
                    decayRate = 0.1,
                    currentPower = 1.0,
                )
            val fastDecay =
                service.calculateMemoryDecay(
                    createdAt = 0L,
                    decayRate = 1.0,
                    currentPower = 1.0,
                )

            assertTrue(fastDecay < slowDecay, "Higher decay rate should result in lower power")
        }
    }
}
