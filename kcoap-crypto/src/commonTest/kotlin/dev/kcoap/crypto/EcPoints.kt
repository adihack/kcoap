package dev.kcoap.crypto

/**
 * Points on P-256 and P-384 with y of both parities, uncompressed (`04 || x || y`) and compressed
 * (`02 || x` for an even y, `03 || x` for an odd one).
 *
 * - G: the base points of SEC 2 v2 §2.4.2 and §2.5.1.
 * - 2G and 3G: their doubles and triples, computed with Python's integers by affine double-and-add and
 *   checked against `y^2 = x^3 - 3x + b`, independently of the code under test.
 * - The RFC 9529 §3 points: PK_R, PK_I and the Initiator's ephemeral G_X of EDHOC trace 2.
 */
internal object EcPoints {
    private const val P256_GX = "6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296"
    private const val P256_GY = "4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5"
    private const val P256_2GX = "7cf27b188d034f7e8a52380304b51ac3c08969e277f21b35a60b48fc47669978"
    private const val P256_2GY = "07775510db8ed040293d9ac69f7430dbba7dade63ce982299e04b79d227873d1"
    private const val P256_3GX = "5ecbe4d1a6330a44c8f7ef951d4bf165e6c6b721efada985fb41661bc6e7fd6c"
    private const val P256_3GY = "8734640c4998ff7e374b06ce1a64a2ecd82ab036384fb83d9a79b127a27d5032"

    private const val P384_GX =
        "aa87ca22be8b05378eb1c71ef320ad746e1d3b628ba79b9859f741e082542a385502f25dbf55296c3a545e3872760ab7"
    private const val P384_GY =
        "3617de4a96262c6f5d9e98bf9292dc29f8f41dbd289a147ce9da3113b5f0b8c00a60b1ce1d7e819d7a431d7c90ea0e5f"
    private const val P384_2GX =
        "08d999057ba3d2d969260045c55b97f089025959a6f434d651d207d19fb96e9e4fe0e86ebe0e64f85b96a9c75295df61"
    private const val P384_2GY =
        "8e80f1fa5b1b3cedb7bfe8dffd6dba74b275d875bc6cc43e904e505f256ab4255ffd43e94d39e22d61501e700a940e80"
    private const val P384_3GX =
        "077a41d4606ffa1464793c7e5fdc7d98cb9d3910202dcd06bea4f240d3566da6b408bbae5026580d02d7e5c70500c831"
    private const val P384_3GY =
        "c995f7ca0b0c42837d0bbe9602a9fc998520b41c85115aa5f7684c0edc111eacc24abd6be4b5d298b65f28600a2f1df1"

    /** RFC 9529 §3.4: PK_R of trace 2. */
    private const val RFC9529_PK_RX = "bbc34960526ea4d32e940cad2a234148ddc21791a12afbcbac93622046dd44f0"
    private const val RFC9529_PK_RY = "4519e257236b2a0ce2023f0931f1f386ca7afda64fcde0108c224c51eabf6072"

    /** RFC 9529 §3.5: PK_I of trace 2. */
    private const val RFC9529_PK_IX = "ac75e9ece3e50bfc8ed60399889522405c47bf16df96660a41298cb4307f7eb6"
    private const val RFC9529_PK_IY = "6e5de611388a4b8a8211334ac7d37ecb52a387d257e6db3c2a93df21ff3affc8"

    /** RFC 9529 §3.3: G_X of trace 2, with the one y-coordinate the trace prints. */
    private const val RFC9529_G_XX = "8af6f430ebe18d34184017a9a11bf511c8dff8f834730b96c1b7c8dbca2fc3b6"
    private const val RFC9529_G_XY = "51e8af6c6edb781601ad1d9c5fa8bf7aa15716c7c06a5d038503c614ff80c9b3"

    const val P256_G_COMPRESSED: String = "03$P256_GX"
    const val P256_G_UNCOMPRESSED: String = "04$P256_GX$P256_GY"
    const val P256_2G_COMPRESSED: String = "03$P256_2GX"
    const val P256_2G_UNCOMPRESSED: String = "04$P256_2GX$P256_2GY"
    const val P256_3G_COMPRESSED: String = "02$P256_3GX"
    const val P256_3G_UNCOMPRESSED: String = "04$P256_3GX$P256_3GY"

    const val P384_G_COMPRESSED: String = "03$P384_GX"
    const val P384_G_UNCOMPRESSED: String = "04$P384_GX$P384_GY"
    const val P384_2G_COMPRESSED: String = "02$P384_2GX"
    const val P384_2G_UNCOMPRESSED: String = "04$P384_2GX$P384_2GY"
    const val P384_3G_COMPRESSED: String = "03$P384_3GX"
    const val P384_3G_UNCOMPRESSED: String = "04$P384_3GX$P384_3GY"

    const val RFC9529_PK_R_COMPRESSED: String = "02$RFC9529_PK_RX"
    const val RFC9529_PK_R_UNCOMPRESSED: String = "04$RFC9529_PK_RX$RFC9529_PK_RY"
    const val RFC9529_PK_I_COMPRESSED: String = "02$RFC9529_PK_IX"
    const val RFC9529_PK_I_UNCOMPRESSED: String = "04$RFC9529_PK_IX$RFC9529_PK_IY"
    const val RFC9529_G_X_COMPRESSED: String = "03$RFC9529_G_XX"
    const val RFC9529_G_X_UNCOMPRESSED: String = "04$RFC9529_G_XX$RFC9529_G_XY"

    /** Every pair above: the curve, the compressed point and the uncompressed one it expands to. */
    val ALL: List<Triple<KeyType, String, String>> = listOf(
        Triple(KeyType.P256, P256_G_COMPRESSED, P256_G_UNCOMPRESSED),
        Triple(KeyType.P256, P256_2G_COMPRESSED, P256_2G_UNCOMPRESSED),
        Triple(KeyType.P256, P256_3G_COMPRESSED, P256_3G_UNCOMPRESSED),
        Triple(KeyType.P256, RFC9529_PK_R_COMPRESSED, RFC9529_PK_R_UNCOMPRESSED),
        Triple(KeyType.P256, RFC9529_PK_I_COMPRESSED, RFC9529_PK_I_UNCOMPRESSED),
        Triple(KeyType.P256, RFC9529_G_X_COMPRESSED, RFC9529_G_X_UNCOMPRESSED),
        Triple(KeyType.P384, P384_G_COMPRESSED, P384_G_UNCOMPRESSED),
        Triple(KeyType.P384, P384_2G_COMPRESSED, P384_2G_UNCOMPRESSED),
        Triple(KeyType.P384, P384_3G_COMPRESSED, P384_3G_UNCOMPRESSED),
    )
}
