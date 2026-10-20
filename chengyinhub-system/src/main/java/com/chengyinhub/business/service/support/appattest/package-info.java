/**
 * Private, offline App Attest verification components. These classes alone never enable a
 * feature, enroll a device, associate a user, consume a challenge, update a counter or grant
 * rewards. The caller owns authenticated account/key binding, one-use expiring challenges,
 * exact authorized client-data bytes, revocation/recovery and atomic counter increases.
 *
 * <p>Primary protocol sources reviewed for this implementation:</p>
 * <ul>
 *   <li>https://developer.apple.com/documentation/devicecheck/validating-apps-that-connect-to-your-server</li>
 *   <li>https://developer.apple.com/documentation/devicecheck/attestation-object-validation-guide</li>
 *   <li>https://developer.apple.com/documentation/devicecheck/establishing-your-app-s-integrity</li>
 *   <li>https://developer.apple.com/documentation/devicecheck/assessing-fraud-risk</li>
 *   <li>https://developer.apple.com/documentation/devicecheck/preparing-to-use-the-app-attest-service</li>
 * </ul>
 *
 * <p>Compatibility is intentionally fail-closed, not a claim of production interoperability.
 * Unit positives use ephemeral synthetic keys/certificates/CMS only. No valid full positive
 * device-produced enrollment or assertion vector was available. Apple's published full
 * example has inconsistent nonce inputs, ED flags and extension wire types and must remain
 * negative. The nonce always commits to authData || SHA256(exact server challenge), with no
 * raw-challenge fallback. Assertions use SHA256withECDSA on authData || SHA256(original
 * clientData), never on an already hashed nonce.</p>
 *
 * <p>Only production AAGUID is supported. Enrollment extensions must be native CBOR UInt32
 * apple_validation_category_01 and text apple_bundle_version_01; assertions use the distinct
 * documented validationCategory / bundleVersion names. Byte-string integer encodings,
 * missing values, aliases, development environments and other dialects are unsupported.
 * CBOR text and byte strings must use definite lengths. Genuine indefinite maps/arrays are
 * supported, but all eight-byte CBOR argument encodings (including small integer values
 * or labels encoded at 64-bit width) are rejected. Original token headers and JDK-strict
 * UTF-8 checks prevent Jackson 2.12.7 coercions; these exclusions limit compatibility.
 * Exact App ID, explicit allowed categories (2/4/5) and bundle versions are required. This
 * does not independently establish iOS versus macOS, an iPhone hardware model, or walking.</p>
 *
 * <p>Both trust anchors must be externally supplied DER and match fixed official DER SHA256
 * fingerprints. Attestation uses the private App Attestation root; receipts independently
 * use public Root G3. PKIX is offline: certificate signatures, validity, CA/path and key usage
 * are checked, but OCSP/CRL revocation is not fetched or claimed. Receipt signer additionally
 * requires the 1.2.840.113635.100.12.15 NULL role marker observed in Apple's audited signer.
 * This role, the observed raw-UTF8 environment field 7 and certificate-DER field 3 dialect
 * need production review; arbitrary G3-issued certificates are not receipt signers.</p>
 *
 * <p>Receipt BER framing (including constructed OCTET STRING) and payload SET ordering are
 * supported through standard BC parsing after an isolated iterative resource preflight.
 * The preflight only bounds encoded structure; BC performs all semantic ASN.1 and crypto.
 * Certificate, SPKI, nonce and ECDSA signature encodings must be canonical DER. Receipts must
 * be ATTEST/production for this App ID and key, bind field 4 to SHA256(challenge), be created
 * within 300 seconds before or 30 seconds after verification time and be unexpired. No
 * receipt refresh, fraud-metric request, key storage or external network call is performed.</p>
 *
 * <p>Before enablement: independently review the framing/token guards and security/dependency
 * policy, settle extension/receipt dialect compatibility, obtain reviewed real-device
 * enrollment AND assertion positives and complete transactional replay/concurrency tests.
 * Synthetic success must never be substituted for any of these gates.</p>
 */
package com.chengyinhub.business.service.support.appattest;
