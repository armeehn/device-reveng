package com.ripostelabs.carlauncher.carlib

/**
 * CarProfile — which car the CAN box is told it is in, and what it is asked at startup.
 *
 * ── Where the table comes from ──────────────────────────────────────────────────────────────────
 *
 *     Sys_camry_air_Supplier_id 6 ──▶ CarPairingVios          (CanbusDataService.java:1256)
 *     Sys_Vehicle_deries 1        ──▶ HiworldCanParseToyota   (CarPairingVios.java:170)
 *     Sys_CarType + Sys_CarInfor_ID ──▶ sendCarTypeToCan ──▶ `02 24 byte 01`
 *
 * canbus2 loads `mCarType` from Sys_CarType and `mCarYear` from Sys_CarInfor_ID
 * (CanDataParseBase.java:262-263). Each row cites the `i4 = N` line in
 * HiworldCanParseToyota.java (C). jadx drops Sienna id 9 and misrenders the 21-24 Sienna constant,
 * so those two are read from the bytecode. Names are canbus2's own, from
 * assets/cartype/simple_cartype_en.json (protocol 6 "Hiworld", make 1 "Toyota").
 *
 * Only pairs with a proven byte are listed. Left out: pairs canbus2 routes to its Crown HI/LO
 * parsers (another protocol), and Noah / Wildlander id 2, which the picker offers but the code
 * maps to nothing, so stock sends the 0xF0 fallback for them.
 */
data class CarProfile(
    val make: String,
    val model: String,
    val years: String,
    val protocol: BoxProtocol,
    /** canbus2's Sys_CarType and Sys_CarInfor_ID for this car; together they name it. */
    val sysCarType: Int,
    val sysCarInforId: Int,
    /** The byte in `02 24 byte 01`. */
    val carType: Int,
    /** Data blocks asked for at startup, `6A 05 01 id` each. */
    val queries: List<Int>,
) {
    /** Stable key for the saved setting. */
    val id: String get() = "${protocol.name.lowercase()}:$sysCarType:$sysCarInforId"

    val label: String get() = "$make $model $years"
}

/** The CAN box family and the canbus2 parser that speaks to it. */
enum class BoxProtocol {
    /** HiWorld box, Toyota and Lexus: HiworldCanParseToyota. */
    HIWORLD_TOYOTA,
}

object CarProfiles {

    /** HiworldCanParseToyota asks every car for these (handleMessage 1001, :1326-1328). */
    private val HIWORLD_TOYOTA_QUERIES = listOf(0x11, 0x82, 0xF0)

    private fun p(type: Int, inforId: Int, byte: Int, make: String, model: String, years: String) =
        CarProfile(make, model, years, BoxProtocol.HIWORLD_TOYOTA, type, inforId, byte, HIWORLD_TOYOTA_QUERIES)

    val ALL: List<CarProfile> = listOf(
        p(10, 2, 0x10, "Toyota", "C-HR", "16-Present"), // C1569
        p(385, 1, 0x14, "Toyota", "IZOA", "16-Present"), // C1655
        p(1, 13, 0x18, "Toyota", "Camry", "10-12"), // C1390
        p(1, 14, 0x19, "Toyota", "Camry", "12-16"), // C1393
        p(1, 15, 0x98, "Toyota", "Camry", "17-Present"), // C1396
        p(1, 16, 0xB5, "Toyota", "Camry", "12-Present (NA)"), // C1399
        p(1, 18, 0x85, "Toyota", "Camry", "06-11 (Portrait)"), // C1402
        p(1, 19, 0x81, "Toyota", "Camry", "12-13 (Portrait)"), // C1405
        p(1, 20, 0x82, "Toyota", "Camry", "15-16 (Portrait)"), // C1408
        p(386, 1, 0x1C, "Toyota", "Yaris", "13-20"), // C1661
        p(386, 2, 0x1D, "Toyota", "Yaris", "20-Present"), // C1663
        p(386, 3, 0xAD, "Toyota", "Yaris", "23-Present (TW)"), // C1665
        p(386, 4, 0xB0, "Toyota", "Yaris", "23-Present (ASEAN)"), // C1667
        p(2, 7, 0x20, "Toyota", "RAV4", "13-16"), // C1415
        p(2, 9, 0x21, "Toyota", "RAV4", "16-21"), // C1418
        p(2, 10, 0x9D, "Toyota", "RAV4", "22-Present"), // C1421
        p(2, 11, 0xB8, "Toyota", "RAV4", "13-Present (NA)"), // C1424
        p(2, 12, 0xAB, "Toyota", "RAV4", "19-Present (TW)"), // C1427
        p(2, 13, 0x8E, "Toyota", "RAV4", "09-16 Auto (Portrait)"), // C1430
        p(5, 7, 0xA1, "Toyota", "Corolla", "23-Present (ASEAN)"), // C1502
        p(5, 8, 0x24, "Toyota", "Corolla", "13-21"), // C1505
        p(5, 9, 0x96, "Toyota", "Corolla", "22-Present"), // C1508
        p(5, 10, 0xA2, "Toyota", "Corolla", "22-Present (Mid-East)"), // C1511
        p(5, 11, 0xA3, "Toyota", "Corolla", "22-Present LO (SA)"), // C1514
        p(5, 12, 0xA4, "Toyota", "Corolla", "22-Present HI (SA)"), // C1517
        p(5, 13, 0xAC, "Toyota", "Corolla", "19-Present (TW)"), // C1520
        p(5, 14, 0x8B, "Toyota", "Corolla", "07-13 Auto (Portrait)"), // C1523
        p(5, 15, 0x8C, "Toyota", "Corolla", "14-17 Auto (Portrait)"), // C1526
        p(387, 1, 0xAE, "Toyota", "Sienta", "23-Present (TW)"), // C1672
        p(3, 3, 0x28, "Toyota", "Reiz", "09-13"), // C1463
        p(3, 10, 0x9B, "Toyota", "Reiz", "05-09 keep air button"), // C1442
        p(3, 11, 0x9C, "Toyota", "Reiz", "05-09 refit air button"), // C1445
        p(3, 12, 0x19, "Toyota", "Reiz", "13-Present"), // C1448
        p(3, 13, 0x84, "Toyota", "Reiz", "10-13 Manual (Portrait)"), // C1450 C1352
        p(3, 14, 0x83, "Toyota", "Reiz", "10-13 Auto (Portrait)"), // C1453
        p(3, 15, 0x82, "Toyota", "Reiz", "13-19 (Portrait)"), // C1456
        p(388, 1, 0x2C, "Toyota", "Prius", "13-Present"), // C1678
        p(388, 2, 0xB6, "Toyota", "Prius", "10-20 (NA)"), // C1680
        p(388, 3, 0xB7, "Toyota", "Prius", "11-17 V (NA)"), // C1682
        p(7, 9, 0x30, "Toyota", "Highlander", "15-21"), // C1541
        p(7, 10, 0x94, "Toyota", "Highlander", "22-Present"), // C1544
        p(7, 11, 0x89, "Toyota", "Highlander", "09-13 Auto (Portrait)"), // C1547
        p(7, 12, 0x8A, "Toyota", "Highlander", "09-13 Manual (Portrait)"), // C1550
        p(7, 13, 0x87, "Toyota", "Highlander", "13-18 Auto (Portrait)"), // C1553
        p(7, 14, 0x88, "Toyota", "Highlander", "13-18 Manual (Portrait)"), // C1556
        p(4, 6, 0x3C, "Toyota", "Prado", "02-09"), // C1470
        p(4, 7, 0x3D, "Toyota", "Prado", "09-13"), // C1473
        p(4, 8, 0x3E, "Toyota", "Prado", "14-17"), // C1476
        p(4, 9, 0x3F, "Toyota", "Prado", "17-Present"), // C1479
        p(4, 14, 0x8D, "Toyota", "Prado", "02-09 (Portrait)"), // C1489
        p(4, 15, 0x83, "Toyota", "Prado", "10-18 Auto (Portrait)"), // C1492
        p(4, 16, 0x84, "Toyota", "Prado", "10-18 Manual (Portrait)"), // C1494 C1352
        p(12, 1, 0x40, "Toyota", "Avalon", "10-13"), // C1594
        p(12, 2, 0x41, "Toyota", "Avalon", "15-17"), // C1596
        p(12, 3, 0x42, "Toyota", "Avalon", "19-Present"), // C1598
        p(11, 6, 0x44, "Toyota", "Sienna", "10-14"), // C1575
        p(11, 7, 0x45, "Toyota", "Sienna", "15-20"), // C1578
        p(11, 8, 0x92, "Toyota", "Sienna", "20-Present"), // C1581
        p(11, 9, 0xB2, "Toyota", "Sienna", "11-17 (NA)"), // bytecode only
        p(11, 10, 0xB3, "Toyota", "Sienna", "18-20 (NA)"), // C1584
        p(11, 11, 0xB9, "Toyota", "Sienna", "21-24 (NA)"), // C1587 bytecode 185
        p(389, 1, 0x48, "Toyota", "Sequoia", "10-12"), // C1687
        p(130, 1, 0x4C, "Toyota", "Tundra", "14-17"), // C1370
        p(130, 2, 0x4D, "Toyota", "Tundra", "18-Present"), // C1372
        p(9, 1, 0x50, "Toyota", "Hilux", "16-22"), // C1564
        p(39, 7, 0x54, "Toyota", "Land Cruiser", "07-15"), // C1748
        p(39, 8, 0x55, "Toyota", "Land Cruiser", "16-Present"), // C1764
        p(39, 12, 0x86, "Toyota", "Land Cruiser", "10-12 (Portrait)"), // C1751 C1351
        p(39, 13, 0x83, "Toyota", "Land Cruiser", "16-21 Auto (Portrait)"), // C1754
        p(39, 14, 0x84, "Toyota", "Land Cruiser", "16-21 Manual (Portrait)"), // C1757
        p(179, 1, 0x58, "Toyota", "Alphard", "09-15"), // C1715
        p(179, 2, 0xAF, "Toyota", "Alphard", "03-07 Hybrid"), // C1717
        p(179, 6, 0x8F, "Toyota", "Alphard", "08-14 (Portrait)"), // C1719
        p(351, 1, 0xA9, "Toyota", "Fortuner", "15-20 (ASEAN)"), // C1606
        p(351, 2, 0xA7, "Toyota", "Fortuner", "21-Present (ASEAN)"), // C1608
        p(351, 3, 0x91, "Toyota", "Fortuner", "18-Present (ASEAN)"), // C1610
        p(390, 1, 0x5C, "Toyota", "Proace City", "19-Present"), // C1692
        p(127, 1, 0x60, "Toyota", "Previa", "07-Present"), // C1732
        p(391, 1, 0x64, "Toyota", "Hiace", "21-Present"), // C1697
        p(178, 1, 0x95, "Toyota", "Crown Kluger", "21-Present"), // C1723
        p(131, 1, 0x93, "Toyota", "Harrier", "22-Present"), // C1377
        p(131, 2, 0xB1, "Toyota", "Harrier", "13-17"), // C1379
        p(133, 1, 0x97, "Toyota", "Frontlander", "22-Present"), // C1729
        p(134, 1, 0x93, "Toyota", "Venza", "22-Present"), // C1726
        p(354, 1, 0xA5, "Toyota", "Granvia", "22-Present"), // C1628
        p(355, 1, 0xA5, "Toyota", "Veloz", "22-Present (ASEAN)"), // C1633
        p(356, 1, 0xA8, "Toyota", "Innova", "23-Present (ASEAN)"), // C1638
        p(353, 1, 0x9F, "Toyota", "Vios", "2022 (ASEAN)"), // C1621
        p(353, 2, 0xA0, "Toyota", "Vios", "23-Present (ASEAN)"), // C1623
        p(357, 1, 0xAA, "Toyota", "Vellfire", "08-14 (ASEAN)"), // C1643
        p(392, 1, 0xB4, "Toyota", "Tacoma", "13-23 (NA)"), // C1702
        p(137, 2, 0x99, "Lexus", "ES", "09-18 HI"), // C1359
        p(137, 3, 0x9A, "Lexus", "ES", "09-18 LO"), // C1361
        p(137, 4, 0x99, "Lexus", "ES", "09 CD"), // C1363
        p(384, 1, 0x99, "Lexus", "ES200", "18 CD"), // C1650
        p(124, 6, 0x6C, "Toyota", "Crown", "14th gen 15-18"), // C1738
        p(6, 3, 0x68, "Toyota", "Levin", "14-18"), // C1533
        p(6, 4, 0x69, "Toyota", "Levin", "19-Present"), // C1535
    )

    /**
     * This unit: Sys_CarType 2, Sys_CarInfor_ID 9, RAV4 16-21, from its sysvar backup
     * (share rav4/reconnect/baselines/hvac-backup-0014/sysvar-keys.txt).
     */
    val DEFAULT: CarProfile = ALL.first { it.sysCarType == 2 && it.sysCarInforId == 9 }

    /** The saved choice, or [DEFAULT] for none or an id this build no longer knows. */
    fun byId(id: String?): CarProfile = ALL.firstOrNull { it.id == id } ?: DEFAULT
}
