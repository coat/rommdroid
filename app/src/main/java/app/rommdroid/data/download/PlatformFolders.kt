package app.rommdroid.data.download

/**
 * Maps a RomM platform onto the folder name used by the ES-DE / RetroDECK
 * directory convention — the layout most Android frontends (ES-DE, RetroArch
 * playlists, Daijisho) expect under a single "ROMs" directory:
 *
 *     ROMs/snes/…   ROMs/psx/…   ROMs/megadrive/…
 *
 * Source of names: https://github.com/retrogamecorps/ES-DE-Directories
 *
 * RomM normalises every platform to a `UniversalPlatformSlug`, and 65 of those
 * already match an ES-DE folder verbatim (nes, snes, psx, ps2, genesis, n64,
 * gba, wii, switch, …), so only the divergent names need an alias below.
 */
object EsDePlatformFolders {

    /** RomM slug → ES-DE folder, for the cases where the two disagree. */
    private val ALIASES: Map<String, String> = mapOf(
        "3ds" to "n3ds",
        "64dd" to "n64dd",
        "acorn-archimedes" to "archimedes",
        "acorn-electron" to "electron",
        "acpc" to "amstradcpc",
        "amiga-cd32" to "amigacd32",
        "amstrad-gx4000" to "gx4000",
        "apple-iigs" to "apple2gs",
        "appleii" to "apple2",
        "arcadia-2001" to "arcadia",
        "astrocade" to "astrocde",
        "atari-jaguar-cd" to "atarijaguar",
        "atari-st" to "atarist",
        "atari-xegs" to "atarixe",
        "atari8bit" to "atari800",
        "c-plus-4" to "plus4",
        "casio-pv-1000" to "pv1000",
        "commodore-cdtv" to "cdtv",
        "creativision" to "crvision",
        "dc" to "dreamcast",
        "dragon-32-slash-64" to "dragon32",
        "epoch-super-cassette-vision" to "scv",
        "fairchild-channel-f" to "channelf",
        "fm-7" to "fm7",
        "fm-towns" to "fmtowns",
        "g-and-w" to "gameandwatch",
        "game-dot-com" to "gamecom",
        "jaguar" to "atarijaguar",
        "lynx" to "atarilynx",
        "mac" to "macintosh",
        "mega-duck-slash-cougar-boy" to "megaduck",
        "msx-turbo" to "msxturbor",
        "msx2plus" to "msxturbor",
        "neo-geo-cd" to "neogeocd",
        "neo-geo-pocket" to "ngp",
        "neo-geo-pocket-color" to "ngpc",
        "neogeoaes" to "neogeo",
        "neogeomvs" to "neogeo",
        "new-nintendo-3ds" to "n3ds",
        "ngc" to "gc",
        "nintendo-dsi" to "nds",
        "odyssey-2" to "odyssey2",
        "pc-8000" to "pc88",
        "pc-8800-series" to "pc88",
        "pc-9800-series" to "pc98",
        "pc-fx" to "pcfx",
        "philips-cd-i" to "cdimono1",
        "pokemon-mini" to "pokemini",
        "sam-coupe" to "samcoupe",
        "sc3000" to "sg-1000",
        "sega32" to "sega32x",
        "sfam" to "sfc",
        "sg1000" to "sg-1000",
        "sharp-x68000" to "x68000",
        "sms" to "mastersystem",
        "sufami-turbo" to "sufami",
        "super-acan" to "supracan",
        "super-nes-cd-rom-system" to "snes",
        "thomson-mo5" to "moto",
        "thomson-to" to "to8",
        "ti-99" to "ti99",
        "ti-994a" to "ti99",
        "tic-80" to "tic80",
        "trs-80-color-computer" to "coco",
        "turbografx-cd" to "tg-cd",
        "vic-20" to "vic20",
        "wasm-4" to "wasm4",
        "win" to "windows",
        "win3x" to "windows3x",
        "win9x" to "windows9x",
        "wonderswan-color" to "wonderswancolor",
        "z-machine" to "zmachine",
        "zxs" to "zxspectrum",
    )

    /** Every folder name in the ES-DE ROMs convention. */
    private val KNOWN: Set<String> = setOf(
        "3do", "adam", "amiga", "amiga1200", "amiga600", "amigacd32",
        "amstradcpc", "androidapps", "androidgames", "apple2", "apple2gs", "arcade",
        "arcadia", "archimedes", "arduboy", "astrocde", "atari2600", "atari5200",
        "atari7800", "atari800", "atarijaguar", "atarilynx", "atarist", "atarixe",
        "atomiswave", "bbcmicro", "c64", "cdimono1", "cdtv", "chailove",
        "channelf", "coco", "colecovision", "consolearcade", "cps", "cps1",
        "cps2", "cps3", "crvision", "daphne", "doom", "dos",
        "dragon32", "dreamcast", "easyrpg", "electron", "emulators", "epic",
        "famicom", "fba", "fbneo", "fds", "flash", "fm7",
        "fmtowns", "gamate", "gameandwatch", "gamecom", "gamegear", "gb",
        "gba", "gbc", "gc", "genesis", "gmaster", "gx4000",
        "intellivision", "j2me", "laserdisc", "lcdgames", "lowresnx", "lutro",
        "macintosh", "mame", "mark3", "mastersystem", "megacd", "megacdjp",
        "megadrive", "megadrivejp", "megaduck", "mess", "model2", "model3",
        "moto", "msx", "msx1", "msx2", "msxturbor", "multivision",
        "n3ds", "n64", "n64dd", "naomi", "naomi2", "naomigd",
        "nds", "neogeo", "neogeocd", "neogeocdjp", "nes", "ngage",
        "ngp", "ngpc", "odyssey2", "openbor", "oric", "palm",
        "pc", "pc88", "pc98", "pcarcade", "pcengine", "pcenginecd",
        "pcfx", "pico8", "plus4", "pokemini", "ports", "ps2",
        "ps3", "psp", "psvita", "psx", "pv1000", "quake",
        "samcoupe", "satellaview", "saturn", "saturnjp", "scummvm", "scv",
        "sega32x", "sega32xjp", "sega32xna", "segacd", "sfc", "sg-1000",
        "sgb", "snes", "snesna", "spectravideo", "steam", "stv",
        "sufami", "supergrafx", "supervision", "supracan", "switch", "symbian",
        "tanodragon", "tg-cd", "tg16", "ti99", "tic80", "to8",
        "type-x", "uzebox", "vectrex", "vic20", "videopac", "vircon32",
        "virtualboy", "vpinball", "vsmile", "wasm4", "wii", "wiiu",
        "windows", "windows3x", "windows9x", "wonderswan", "wonderswancolor", "x1",
        "x68000", "zmachine", "zx81", "zxspectrum",
    )

    /**
     * The subfolder name to use for a platform, resolved in order of confidence:
     * an explicit alias, the RomM slug itself when it is already an ES-DE name,
     * then the same two checks against the server-side folder name.
     *
     * Falls back to the server's own folder name so an unrecognised platform
     * still lands somewhere sensible rather than being dropped.
     */
    fun forPlatform(slug: String, fsSlug: String): String {
        val s = slug.lowercase()
        ALIASES[s]?.let { return it }
        if (s in KNOWN) return s

        val fs = fsSlug.lowercase()
        ALIASES[fs]?.let { return it }
        if (fs in KNOWN) return fs

        return fsSlug.ifBlank { slug }
    }

    /** True when [name] is part of the ES-DE convention — used to flag typos in the UI. */
    fun isConventional(name: String): Boolean = name.lowercase() in KNOWN
}
