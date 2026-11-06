/*
 * edhoc_peer: libedhoc as an EDHOC Initiator or Responder (RFC 9528), driven line by line over stdin and
 * stdout, so that kcoap's JVM interop tests can run one EDHOC session against it per process:
 *
 *   docker run -i --rm kcoap/libedhoc:0363bd3 edhoc_peer --role responder --method 0 --suite 2 ...
 *
 * The peer uses libedhoc's own reference cipher suites (mbedTLS PSA, Compact25519, liboqs, XKCP) and never
 * generates authentication keys or credentials itself: the test hands it its own private key and credential,
 * and the credential and public key it must accept from kcoap. Ephemeral keys are libedhoc's, fresh per run.
 *
 * Options (hex values are lower- or upper-case, "-" stands for the empty byte string):
 *
 *   --role initiator|responder
 *   --method M              Initiator: the method it uses. Responder: a method it accepts (repeatable).
 *   --suite S               Repeatable. Initiator: its suites in order of preference. Responder: the suites it
 *                           configures; libedhoc uses the last one (see "Responder suites" below).
 *   --cid HEX               own connection identifier (C_I or C_R), as raw bytes
 *   --key TYPE:HEX          own private authentication key, TYPE one of
 *                             ed25519   64 bytes: seed || public key (Compact25519 format)
 *                             x25519    32 bytes, static Diffie-Hellman
 *                             p256-sign, p256-dh, p384-sign, p384-dh    the private scalar
 *                             mldsa44   2560 bytes: the expanded FIPS 204 secret key
 *   --own-kid HEX --own-cred HEX     own credential referenced by 'kid'; CRED_x is the given CBOR item (a CCS)
 *   --own-x5chain HEX[,HEX]          own credential sent by value as an X.509 chain, end entity first
 *   --peer-kid HEX --peer-cred HEX   the 'kid' and CRED_x (CBOR item) the peer must present
 *   --peer-x5chain HEX               the end-entity certificate the peer must present in x5chain
 *   --peer-key HEX          the peer's public authentication key in the form libedhoc's suite expects:
 *                           Ed25519/X25519 32 bytes; ES256 65 bytes (04||x||y); P-256 static DH 32 bytes (x);
 *                           ES384 97 bytes; P-384 static DH 48 bytes (x); ML-DSA-44 1312 bytes
 *   --message4              the Responder sends message_4 and the Initiator expects it
 *   --oscore-secret-length N   length of the exported OSCORE Master Secret (default 16)
 *
 * Line protocol, one line per item, tokens separated by single spaces:
 *
 *   stdin   msg <hex>             the next message from the other side (an EDHOC message or error message)
 *   stdout  msg <hex>             a message for the other side: message_1..4, or an error message
 *   stdout  peer_error <code> [<suite>,<suite>...]   an error message arrived (SUITES_R for code 2)
 *   stdout  retry <suite>         the Initiator received error code 2 and restarts with this suite selected;
 *                                 the next line is msg with the new message_1
 *   stdout  failed <ret> <code>   processing failed with libedhoc return value <ret>; the error message
 *                                 with ERR_CODE <code> follows as msg (none for a failure on an error message)
 *   stdout  result <name> <hex>   after a completed session, in this order:
 *                                   suite, c_i, c_r, oscore_secret, oscore_salt, sender_id, recipient_id,
 *                                   exporter, prk_out_check, exporter_updated, prk_out_check_updated
 *   stdout  done                  the session completed
 *
 * Results: "exporter" is EDHOC_Exporter(32768, "kcoap", 32); "prk_out_check" is EDHOC_Expand(PRK_out,
 * "kcoap-prk-out-check", 32), which proves agreement on PRK_out without revealing it (libedhoc keeps PRK_out
 * in a non-exportable key slot, reached through its internal header); the "_updated" values are the same
 * after EDHOC_KeyUpdate with context "kcoap-key-update" (RFC 9528 Appendix H).
 *
 * Responder suites: libedhoc accepts message_1 only if its selected suite equals the last configured suite
 * (library/core/classic/edhoc_classic_message_1.c, step 3b), and it binds one crypto backend per context, so
 * the peer binds the backend of the last --suite. On error code 2 it sends SUITES_R = the configured suites,
 * as libedhoc's own negotiation test does. All of libedhoc's log goes to stderr.
 *
 * Exit status: 0 for a completed session, 1 for a failed one, 2 for invalid options.
 */

#include <edhoc/edhoc.h>
#include <edhoc/cipher_suite.h>

/* Internal: struct edhoc_context and its key slots, for PRK_out (pinned libedhoc commit). */
#include "edhoc_context_internal.h"
#include "edhoc_key_slot_internal.h"

#include <psa/crypto.h>

#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define MAX_MESSAGE ((size_t)16384)
#define MAX_KEY ((size_t)4096)
#define MAX_CRED ((size_t)8192)
#define MAX_SUITES ((size_t)3)
#define MAX_METHODS ((size_t)4)
#define CHECK_LENGTH ((size_t)32)
#define EXPORTER_LABEL ((size_t)32768)
#define OSCORE_SALT_LENGTH ((size_t)8)
#define OSCORE_ID_MAX ((size_t)8)

#if defined(CONFIG_LIBEDHOC_CIPHER_SUITE_PQC_1_ENABLE) && CONFIG_LIBEDHOC_CIPHER_SUITE_PQC_1_ENABLE
extern int edhoc_cipher_suite_pqc_1_import_signing_key(const uint8_t *signing_key, size_t signing_key_length,
							void *key_id);
#endif

struct bytes {
	uint8_t *value;
	size_t length;
};

enum key_kind {
	KEY_NONE,
	KEY_ED25519,
	KEY_X25519,
	KEY_P256_SIGN,
	KEY_P256_DH,
	KEY_P384_SIGN,
	KEY_P384_DH,
	KEY_MLDSA44,
};

struct config {
	bool is_initiator;
	enum edhoc_method methods[MAX_METHODS];
	size_t method_count;
	int32_t suites[MAX_SUITES];
	size_t suite_count;
	struct bytes cid;
	enum key_kind key_kind;
	struct bytes key;
	struct bytes own_kid;
	struct bytes own_cred;
	struct bytes own_chain[2];
	size_t own_chain_count;
	struct bytes peer_kid;
	struct bytes peer_cred;
	struct bytes peer_x5chain;
	struct bytes peer_key;
	bool message4;
	size_t oscore_secret_length;
};

static struct config config = { .oscore_secret_length = 16 };

/*
 * The protocol stream. libedhoc logs warnings and information to stdout and errors to stderr
 * (backends/log/include/edhoc_backend_log.h), so main() moves the original stdout here and points file
 * descriptor 1 at stderr: the protocol channel carries nothing but protocol lines.
 */
static FILE *protocol;

/* --- small helpers ------------------------------------------------------------------------------------ */

static void fail_usage(const char *message)
{
	fprintf(stderr, "edhoc_peer: %s\n", message);
	exit(2);
}

static int hex_digit(char c)
{
	if (c >= '0' && c <= '9')
		return c - '0';
	if (c >= 'a' && c <= 'f')
		return c - 'a' + 10;
	if (c >= 'A' && c <= 'F')
		return c - 'A' + 10;
	return -1;
}

static struct bytes parse_hex(const char *text)
{
	struct bytes result = { 0 };
	if (0 == strcmp(text, "-"))
		return result;
	const size_t length = strlen(text);
	if (0 != length % 2)
		fail_usage("odd number of hex digits");
	result.value = malloc(length / 2 + 1);
	result.length = length / 2;
	for (size_t i = 0; i < result.length; ++i) {
		const int high = hex_digit(text[2 * i]);
		const int low = hex_digit(text[2 * i + 1]);
		if (high < 0 || low < 0)
			fail_usage("invalid hex digit");
		result.value[i] = (uint8_t)((high << 4) | low);
	}
	return result;
}

static void print_hex(const char *prefix, const uint8_t *value, size_t length)
{
	fprintf(protocol, "%s ", prefix);
	if (0 == length)
		fprintf(protocol, "-");
	for (size_t i = 0; i < length; ++i)
		fprintf(protocol, "%02x", value[i]);
	fprintf(protocol, "\n");
	fflush(protocol);
}

static void print_result(const char *name, const uint8_t *value, size_t length)
{
	char prefix[64];
	snprintf(prefix, sizeof(prefix), "result %s", name);
	print_hex(prefix, value, length);
}

/* Reads "msg <hex>" from stdin; returns false at end of input. */
static bool read_message(uint8_t *buffer, size_t *length)
{
	static char line[2 * MAX_MESSAGE + 16];
	if (NULL == fgets(line, sizeof(line), stdin))
		return false;
	line[strcspn(line, "\r\n")] = '\0';
	if (0 != strncmp(line, "msg ", 4))
		fail_usage("expected a msg line");
	struct bytes message = parse_hex(line + 4);
	if (message.length > MAX_MESSAGE)
		fail_usage("message too long");
	if (message.length > 0)
		memcpy(buffer, message.value, message.length);
	*length = message.length;
	free(message.value);
	return true;
}

/* An error message starts with ERR_CODE, an integer; message_2..4 start with a byte string (RFC 9528 §5). */
static bool is_error_message(const uint8_t *message, size_t length)
{
	if (0 == length)
		return false;
	const int major = message[0] >> 5;
	return 0 == major || 1 == major;
}

static void zeroize(void *buffer, size_t length)
{
	volatile uint8_t *bytes = buffer;
	while (length--)
		*bytes++ = 0;
}

static const struct edhoc_platform platform = { .zeroize = zeroize };

/* --- credentials callbacks ---------------------------------------------------------------------------- */

static int import_private_key(void *key_id)
{
	memset(key_id, 0, CONFIG_LIBEDHOC_KEY_ID_LEN);
	psa_key_attributes_t attributes = PSA_KEY_ATTRIBUTES_INIT;
	psa_set_key_lifetime(&attributes, PSA_KEY_LIFETIME_VOLATILE);

	switch (config.key_kind) {
	case KEY_ED25519:
		/* Signed with Compact25519 from an exported RAW_DATA seed||public key, as libedhoc's tests do. */
		psa_set_key_usage_flags(&attributes, PSA_KEY_USAGE_EXPORT);
		psa_set_key_type(&attributes, PSA_KEY_TYPE_RAW_DATA);
		break;
	case KEY_X25519:
		psa_set_key_usage_flags(&attributes, PSA_KEY_USAGE_DERIVE);
		psa_set_key_algorithm(&attributes, PSA_ALG_ECDH);
		psa_set_key_type(&attributes, PSA_KEY_TYPE_ECC_KEY_PAIR(PSA_ECC_FAMILY_MONTGOMERY));
		break;
	case KEY_P256_SIGN:
	case KEY_P384_SIGN:
		psa_set_key_usage_flags(&attributes, PSA_KEY_USAGE_SIGN_HASH);
		psa_set_key_algorithm(&attributes, PSA_ALG_ECDSA(KEY_P256_SIGN == config.key_kind ? PSA_ALG_SHA_256 :
												       PSA_ALG_SHA_384));
		psa_set_key_type(&attributes, PSA_KEY_TYPE_ECC_KEY_PAIR(PSA_ECC_FAMILY_SECP_R1));
		break;
	case KEY_P256_DH:
	case KEY_P384_DH:
		psa_set_key_usage_flags(&attributes, PSA_KEY_USAGE_DERIVE);
		psa_set_key_algorithm(&attributes, PSA_ALG_ECDH);
		psa_set_key_type(&attributes, PSA_KEY_TYPE_ECC_KEY_PAIR(PSA_ECC_FAMILY_SECP_R1));
		break;
	case KEY_MLDSA44:
#if defined(CONFIG_LIBEDHOC_CIPHER_SUITE_PQC_1_ENABLE) && CONFIG_LIBEDHOC_CIPHER_SUITE_PQC_1_ENABLE
		return edhoc_cipher_suite_pqc_1_import_signing_key(config.key.value, config.key.length, key_id);
#else
		return EDHOC_ERROR_NOT_SUPPORTED;
#endif
	default:
		return EDHOC_ERROR_CREDENTIALS_FAILURE;
	}

	psa_key_id_t handle = PSA_KEY_ID_NULL;
	const psa_status_t status = psa_import_key(&attributes, config.key.value, config.key.length, &handle);
	if (PSA_SUCCESS != status) {
		fprintf(stderr, "edhoc_peer: psa_import_key failed: %d\n", (int)status);
		return EDHOC_ERROR_CREDENTIALS_FAILURE;
	}
	memcpy(key_id, &handle, sizeof(handle));
	return EDHOC_SUCCESS;
}

static int select_local(void *user_context, const struct edhoc_call_context *call_context,
			struct edhoc_credential_selected *selected)
{
	(void)user_context;
	(void)call_context;

	if (config.own_chain_count > 0) {
		selected->asymmetric.label = EDHOC_COSE_HEADER_X509_CHAIN;
		selected->asymmetric.x509_chain.count = config.own_chain_count;
		for (size_t i = 0; i < config.own_chain_count; ++i) {
			selected->asymmetric.x509_chain.certificate[i].value = config.own_chain[i].value;
			selected->asymmetric.x509_chain.certificate[i].length = config.own_chain[i].length;
		}
	} else {
		selected->asymmetric.label = EDHOC_COSE_HEADER_KID;
		selected->asymmetric.kid.identifier.value = config.own_kid.value;
		selected->asymmetric.kid.identifier.length = config.own_kid.length;
		selected->asymmetric.kid.credential.value = config.own_cred.value;
		selected->asymmetric.kid.credential.length = config.own_cred.length;
		selected->asymmetric.kid.format = EDHOC_CREDENTIAL_FORMAT_CBOR_ENCODED;
	}
	return import_private_key(selected->asymmetric.private_key_id);
}

static bool equal(const struct edhoc_buffer *received, const struct bytes *expected)
{
	return received->length == expected->length &&
	       (0 == expected->length || 0 == memcmp(received->value, expected->value, expected->length));
}

static int authenticate_peer(void *user_context, const struct edhoc_call_context *call_context,
			     const struct edhoc_credential_received *received, struct edhoc_credential_trusted *trusted)
{
	(void)user_context;
	(void)call_context;

	switch (received->label) {
	case EDHOC_COSE_HEADER_KID:
		if (0 == config.peer_cred.length || !equal(&received->kid.identifier, &config.peer_kid)) {
			fprintf(stderr, "edhoc_peer: unexpected kid\n");
			return EDHOC_ERROR_CREDENTIALS_FAILURE;
		}
		trusted->asymmetric.credential.value = config.peer_cred.value;
		trusted->asymmetric.credential.length = config.peer_cred.length;
		trusted->asymmetric.format = EDHOC_CREDENTIAL_FORMAT_CBOR_ENCODED;
		break;
	case EDHOC_COSE_HEADER_X509_CHAIN:
		if (0 == received->x509_chain.count || !equal(&received->x509_chain.certificate[0], &config.peer_x5chain)) {
			fprintf(stderr, "edhoc_peer: unexpected certificate\n");
			return EDHOC_ERROR_CREDENTIALS_FAILURE;
		}
		/* CRED_x is the received end-entity certificate (RFC 9528 §3.5.2). */
		trusted->asymmetric.credential = received->x509_chain.certificate[0];
		trusted->asymmetric.format = EDHOC_CREDENTIAL_FORMAT_RAW;
		break;
	default:
		fprintf(stderr, "edhoc_peer: unsupported ID_CRED label %d\n", (int)received->label);
		return EDHOC_ERROR_CREDENTIALS_FAILURE;
	}
	trusted->asymmetric.public_key.value = config.peer_key.value;
	trusted->asymmetric.public_key.length = config.peer_key.length;
	return EDHOC_SUCCESS;
}

static const struct edhoc_credentials credentials = {
	.select_local = select_local,
	.authenticate_peer = authenticate_peer,
};

/* --- context ------------------------------------------------------------------------------------------ */

/* Configures a fresh context with the first [suite_count] suites of the configuration; the last is used. */
static void setup_context(struct edhoc_context *context, size_t suite_count)
{
	struct edhoc_cipher_suite suites[MAX_SUITES];
	for (size_t i = 0; i < suite_count; ++i) {
		const struct edhoc_cipher_suite *params =
			edhoc_cipher_suite_get_params((enum edhoc_cipher_suite_id)config.suites[i]);
		if (NULL == params)
			fail_usage("cipher suite not compiled into libedhoc");
		suites[i] = *params;
	}
	const struct edhoc_crypto *crypto =
		edhoc_cipher_suite_get_crypto((enum edhoc_cipher_suite_id)config.suites[suite_count - 1]);
	const struct edhoc_buffer cid = { .value = config.cid.value, .length = config.cid.length };

	if (EDHOC_SUCCESS != edhoc_context_init(context) ||
	    EDHOC_SUCCESS != edhoc_set_methods(context, config.methods, config.method_count) ||
	    EDHOC_SUCCESS != edhoc_set_cipher_suites(context, suites, suite_count) ||
	    EDHOC_SUCCESS != edhoc_set_connection_id(context, &cid) || EDHOC_SUCCESS != edhoc_bind_crypto(context, crypto) ||
	    EDHOC_SUCCESS != edhoc_bind_credentials(context, &credentials) ||
	    EDHOC_SUCCESS != edhoc_bind_platform(context, &platform))
		fail_usage("libedhoc rejected the configuration");
}

/* After a failed processing step: report it and send the error message libedhoc's context calls for. */
static int report_failure(struct edhoc_context *context, int ret)
{
	enum edhoc_error_code code = EDHOC_ERROR_CODE_UNSPECIFIED_ERROR;
	(void)edhoc_error_get_code(context, &code);
	if (EDHOC_ERROR_CODE_SUCCESS == code)
		code = EDHOC_ERROR_CODE_UNSPECIFIED_ERROR;
	fprintf(protocol, "failed %d %d\n", ret, (int)code);
	fflush(protocol);

	char text[64];
	int32_t suites[MAX_SUITES];
	struct edhoc_error_info info = { 0 };
	switch (code) {
	case EDHOC_ERROR_CODE_WRONG_SELECTED_CIPHER_SUITE: {
		size_t own_count = 0;
		size_t peer_count = 0;
		int32_t peer_suites[MAX_SUITES];
		if (EDHOC_SUCCESS != edhoc_error_get_cipher_suites(context, suites, MAX_SUITES, &own_count, peer_suites,
								   MAX_SUITES, &peer_count))
			return 1;
		info.cipher_suites = suites;
		info.entries_size = MAX_SUITES;
		info.entries_length = own_count;
		break;
	}
	case EDHOC_ERROR_CODE_UNKNOWN_CREDENTIAL_REFERENCED:
		break;
	default:
		snprintf(text, sizeof(text), "libedhoc error %d", ret);
		info.text_string = text;
		info.entries_size = sizeof(text);
		info.entries_length = strlen(text);
		break;
	}

	uint8_t message[256];
	size_t length = 0;
	if (EDHOC_SUCCESS == edhoc_message_error_compose(context, message, sizeof(message), &length, code, &info))
		print_hex("msg", message, length);
	return 1;
}

/* Reports a received error message; returns its code and fills [suites] with SUITES_R for code 2. */
static enum edhoc_error_code report_peer_error(struct edhoc_context *context, const uint8_t *message, size_t length,
					       int32_t *suites, size_t *suite_count)
{
	enum edhoc_error_code code = EDHOC_ERROR_CODE_UNSPECIFIED_ERROR;
	char text[256] = { 0 };
	int32_t received_suites[MAX_SUITES] = { 0 };
	struct edhoc_error_info info = { .entries_size = 0 };
	/* The info buffer is chosen by peeking at ERR_CODE: 1 has a text string, 2 a suite list. */
	if (length >= 1 && 0x02 == message[0]) {
		info.cipher_suites = received_suites;
		info.entries_size = MAX_SUITES;
	} else {
		info.text_string = text;
		info.entries_size = sizeof(text) - 1;
	}
	const int ret = edhoc_message_error_process(context, message, length, &code, &info);
	if (EDHOC_SUCCESS != ret) {
		fprintf(protocol, "failed %d %d\n", ret, (int)EDHOC_ERROR_CODE_UNSPECIFIED_ERROR);
		fflush(protocol);
		return EDHOC_ERROR_CODE_UNSPECIFIED_ERROR;
	}
	fprintf(protocol, "peer_error %d", (int)code);
	if (EDHOC_ERROR_CODE_WRONG_SELECTED_CIPHER_SUITE == code) {
		for (size_t i = 0; i < info.entries_length; ++i)
			fprintf(protocol, "%s%d", 0 == i ? " " : ",", (int)received_suites[i]);
		*suite_count = info.entries_length;
		memcpy(suites, received_suites, sizeof(received_suites[0]) * info.entries_length);
	}
	fprintf(protocol, "\n");
	fflush(protocol);
	return code;
}

static int print_results(struct edhoc_context *context)
{
	uint8_t secret[64];
	uint8_t salt[OSCORE_SALT_LENGTH];
	uint8_t sender_id[OSCORE_ID_MAX];
	uint8_t recipient_id[OSCORE_ID_MAX];
	size_t sender_id_length = 0;
	size_t recipient_id_length = 0;
	if (config.oscore_secret_length > sizeof(secret))
		fail_usage("OSCORE secret too long");

	int32_t suite = context->negotiation.cipher_suite.entry[context->negotiation.selected_cipher_suite_index].value;
	char suite_text[16];
	snprintf(suite_text, sizeof(suite_text), "%d", (int)suite);
	fprintf(protocol, "result suite %s\n", suite_text);
	const struct connection_id *own = &context->negotiation.connection_id;
	const struct connection_id *peer = &context->negotiation.peer_connection_id;
	print_result("c_i", config.is_initiator ? own->value : peer->value,
		     config.is_initiator ? own->length : peer->length);
	print_result("c_r", config.is_initiator ? peer->value : own->value,
		     config.is_initiator ? peer->length : own->length);

	int ret = edhoc_export_oscore_context_raw(context, secret, config.oscore_secret_length, salt, sizeof(salt),
						  sender_id, sizeof(sender_id), &sender_id_length, recipient_id,
						  sizeof(recipient_id), &recipient_id_length);
	if (EDHOC_SUCCESS != ret) {
		fprintf(protocol, "failed %d 0\n", ret);
		return 1;
	}
	print_result("oscore_secret", secret, config.oscore_secret_length);
	print_result("oscore_salt", salt, sizeof(salt));
	print_result("sender_id", sender_id, sender_id_length);
	print_result("recipient_id", recipient_id, recipient_id_length);

	static const uint8_t exporter_context[] = { 'k', 'c', 'o', 'a', 'p' };
	static const uint8_t check_info[] = "kcoap-prk-out-check";
	static const uint8_t update_context[] = "kcoap-key-update";
	const struct edhoc_crypto *crypto = &context->interfaces.crypto;

	for (int round = 0; round < 2; ++round) {
		uint8_t exported[CHECK_LENGTH];
		uint8_t check[CHECK_LENGTH];
		ret = edhoc_export_raw(context, EXPORTER_LABEL, exporter_context, sizeof(exporter_context), exported,
				       sizeof(exported));
		if (EDHOC_SUCCESS != ret) {
			fprintf(protocol, "failed %d 0\n", ret);
			return 1;
		}
		/* The exporter call has derived PRK_out into its slot. */
		ret = crypto->expand_raw(context->user_context, edhoc_key_slot_id(context, EDHOC_KEY_SLOT_PRK_OUT),
					 check_info, sizeof(check_info) - 1, check, sizeof(check));
		if (EDHOC_SUCCESS != ret) {
			fprintf(protocol, "failed %d 0\n", ret);
			return 1;
		}
		print_result(0 == round ? "exporter" : "exporter_updated", exported, sizeof(exported));
		print_result(0 == round ? "prk_out_check" : "prk_out_check_updated", check, sizeof(check));
		if (0 == round) {
			ret = edhoc_export_key_update(context, update_context, sizeof(update_context) - 1);
			if (EDHOC_SUCCESS != ret) {
				fprintf(protocol, "failed %d 0\n", ret);
				return 1;
			}
		}
	}
	fprintf(protocol, "done\n");
	fflush(protocol);
	return 0;
}

/* --- roles -------------------------------------------------------------------------------------------- */

static int run_initiator(void)
{
	static struct edhoc_context context;
	static uint8_t message[MAX_MESSAGE];
	size_t length = 0;
	size_t offered = 1;
	int ret;

	for (;;) {
		setup_context(&context, offered);
		ret = edhoc_message_1_compose(&context, message, sizeof(message), &length);
		if (EDHOC_SUCCESS != ret) {
			fprintf(protocol, "failed %d 1\n", ret);
			return 1;
		}
		print_hex("msg", message, length);
		if (!read_message(message, &length))
			return 1;
		if (!is_error_message(message, length))
			break;
		int32_t suites_r[MAX_SUITES];
		size_t suites_r_count = 0;
		const enum edhoc_error_code code = report_peer_error(&context, message, length, suites_r, &suites_r_count);
		if (EDHOC_ERROR_CODE_WRONG_SELECTED_CIPHER_SUITE != code)
			return 1;
		/* RFC 9528 §6.3.1: select the most preferred own suite in SUITES_R, offering all preferred ones. */
		size_t next = 0;
		for (; next < config.suite_count; ++next) {
			bool listed = false;
			for (size_t i = 0; i < suites_r_count; ++i)
				listed = listed || suites_r[i] == config.suites[next];
			if (listed)
				break;
		}
		if (next == config.suite_count || next + 1 == offered)
			return 1;
		offered = next + 1;
		fprintf(protocol, "retry %d\n", (int)config.suites[next]);
		fflush(protocol);
	}

	ret = edhoc_message_2_process(&context, message, length);
	if (EDHOC_SUCCESS != ret)
		return report_failure(&context, ret);
	ret = edhoc_message_3_compose(&context, message, sizeof(message), &length);
	if (EDHOC_SUCCESS != ret)
		return report_failure(&context, ret);
	print_hex("msg", message, length);

	if (config.message4) {
		if (!read_message(message, &length))
			return 1;
		if (is_error_message(message, length)) {
			int32_t ignored[MAX_SUITES];
			size_t ignored_count = 0;
			(void)report_peer_error(&context, message, length, ignored, &ignored_count);
			return 1;
		}
		ret = edhoc_message_4_process(&context, message, length);
		if (EDHOC_SUCCESS != ret)
			return report_failure(&context, ret);
	}
	return print_results(&context);
}

static int run_responder(void)
{
	static struct edhoc_context context;
	static uint8_t message[MAX_MESSAGE];
	size_t length = 0;
	int ret;

	setup_context(&context, config.suite_count);
	if (!read_message(message, &length))
		return 1;
	ret = edhoc_message_1_process(&context, message, length);
	if (EDHOC_SUCCESS != ret)
		return report_failure(&context, ret);
	ret = edhoc_message_2_compose(&context, message, sizeof(message), &length);
	if (EDHOC_SUCCESS != ret)
		return report_failure(&context, ret);
	print_hex("msg", message, length);

	if (!read_message(message, &length))
		return 1;
	if (is_error_message(message, length)) {
		int32_t ignored[MAX_SUITES];
		size_t ignored_count = 0;
		(void)report_peer_error(&context, message, length, ignored, &ignored_count);
		return 1;
	}
	ret = edhoc_message_3_process(&context, message, length);
	if (EDHOC_SUCCESS != ret)
		return report_failure(&context, ret);

	if (config.message4) {
		ret = edhoc_message_4_compose(&context, message, sizeof(message), &length);
		if (EDHOC_SUCCESS != ret) {
			fprintf(protocol, "failed %d 0\n", ret);
			return 1;
		}
		print_hex("msg", message, length);
	}
	return print_results(&context);
}

/* --- options ------------------------------------------------------------------------------------------ */

static enum key_kind parse_key_kind(const char *name)
{
	static const struct {
		const char *name;
		enum key_kind kind;
	} kinds[] = {
		{ "ed25519", KEY_ED25519 },	{ "x25519", KEY_X25519 },	{ "p256-sign", KEY_P256_SIGN },
		{ "p256-dh", KEY_P256_DH },	{ "p384-sign", KEY_P384_SIGN }, { "p384-dh", KEY_P384_DH },
		{ "mldsa44", KEY_MLDSA44 },
	};
	for (size_t i = 0; i < sizeof(kinds) / sizeof(kinds[0]); ++i)
		if (0 == strcmp(name, kinds[i].name))
			return kinds[i].kind;
	fail_usage("unknown key type");
	return KEY_NONE;
}

static void parse_options(int argc, char **argv)
{
	for (int i = 1; i < argc; ++i) {
		const char *option = argv[i];
		if (0 == strcmp(option, "--message4")) {
			config.message4 = true;
			continue;
		}
		if (i + 1 >= argc)
			fail_usage("missing option value");
		const char *value = argv[++i];
		if (0 == strcmp(option, "--role")) {
			config.is_initiator = 0 == strcmp(value, "initiator");
			if (!config.is_initiator && 0 != strcmp(value, "responder"))
				fail_usage("unknown role");
		} else if (0 == strcmp(option, "--method")) {
			if (config.method_count == MAX_METHODS)
				fail_usage("too many methods");
			config.methods[config.method_count++] = (enum edhoc_method)atoi(value);
		} else if (0 == strcmp(option, "--suite")) {
			if (config.suite_count == MAX_SUITES)
				fail_usage("too many suites");
			config.suites[config.suite_count++] = (int32_t)atoi(value);
		} else if (0 == strcmp(option, "--cid")) {
			config.cid = parse_hex(value);
		} else if (0 == strcmp(option, "--key")) {
			const char *colon = strchr(value, ':');
			if (NULL == colon)
				fail_usage("--key needs TYPE:HEX");
			char kind[16] = { 0 };
			strncpy(kind, value, (size_t)(colon - value) < sizeof(kind) - 1 ? (size_t)(colon - value) :
										      sizeof(kind) - 1);
			config.key_kind = parse_key_kind(kind);
			config.key = parse_hex(colon + 1);
		} else if (0 == strcmp(option, "--own-kid")) {
			config.own_kid = parse_hex(value);
		} else if (0 == strcmp(option, "--own-cred")) {
			config.own_cred = parse_hex(value);
		} else if (0 == strcmp(option, "--own-x5chain")) {
			char *copy = strdup(value);
			for (char *part = strtok(copy, ","); NULL != part; part = strtok(NULL, ",")) {
				if (config.own_chain_count == 2)
					fail_usage("at most two certificates");
				config.own_chain[config.own_chain_count++] = parse_hex(part);
			}
			free(copy);
		} else if (0 == strcmp(option, "--peer-kid")) {
			config.peer_kid = parse_hex(value);
		} else if (0 == strcmp(option, "--peer-cred")) {
			config.peer_cred = parse_hex(value);
		} else if (0 == strcmp(option, "--peer-x5chain")) {
			config.peer_x5chain = parse_hex(value);
		} else if (0 == strcmp(option, "--peer-key")) {
			config.peer_key = parse_hex(value);
		} else if (0 == strcmp(option, "--oscore-secret-length")) {
			config.oscore_secret_length = (size_t)atoi(value);
		} else {
			fail_usage("unknown option");
		}
	}
	if (0 == config.method_count || 0 == config.suite_count || KEY_NONE == config.key_kind)
		fail_usage("--method, --suite and --key are required");
	if (0 == config.own_chain_count && 0 == config.own_cred.length)
		fail_usage("an own credential is required");
}

int main(int argc, char **argv)
{
	protocol = fdopen(dup(STDOUT_FILENO), "w");
	if (NULL == protocol || dup2(STDERR_FILENO, STDOUT_FILENO) < 0)
		fail_usage("cannot set up the protocol stream");
	parse_options(argc, argv);
	if (PSA_SUCCESS != psa_crypto_init())
		fail_usage("psa_crypto_init failed");
	return config.is_initiator ? run_initiator() : run_responder();
}
