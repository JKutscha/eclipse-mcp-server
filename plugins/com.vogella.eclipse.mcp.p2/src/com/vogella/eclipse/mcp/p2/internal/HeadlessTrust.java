package com.vogella.eclipse.mcp.p2.internal;

import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.bouncycastle.openpgp.PGPPublicKey;

import org.eclipse.equinox.p2.core.IProvisioningAgent;
import org.eclipse.equinox.p2.core.UIServices;

/**
 * Answers p2's trust and credential prompts without a human.
 * <p>
 * The IDE's own {@code UIServices} raises a modal dialog, which blocks the
 * provisioning job until somebody clicks it. From a client that is
 * indistinguishable from a slow download, so an unattended update hangs until
 * the call times out and the real cause never surfaces. This answers instead.
 * <p>
 * Unsigned content is accepted by default on this path, because an install the
 * server performs is unattended by definition and there is nobody to click the
 * dialog. This is not bounded by which sites are configured, because a client can
 * configure a new one through eclipse_add_repository. Whatever was accepted
 * is reported, so a trusted install is auditable rather than silent.
 */
final class HeadlessTrust extends UIServices {

	private final boolean trustUnsigned;

	private final List<String> prompts = new ArrayList<>();

	private volatile boolean prompted;

	HeadlessTrust(boolean trustUnsigned) {
		this.trustUnsigned = trustUnsigned;
	}

	boolean prompted() {
		return prompted;
	}

	/** What p2 asked about, whether it was then trusted or refused. */
	synchronized List<String> prompts() {
		return List.copyOf(prompts);
	}

	@Override
	public TrustInfo getTrustInfo(Certificate[][] untrustedChains, String[] unsignedDetail) {
		return trust(untrustedChains, List.of(), unsignedDetail);
	}

	/**
	 * The overload p2 actually reaches for a signed artifact.
	 * <p>
	 * Its default implementation on {@link UIServices} drops the PGP keys and calls
	 * the pair above, which then answered "no key is trusted" and cancelled the
	 * install: a locally built repository could not be installed however the caller
	 * asked. It has to be overridden rather than inherited.
	 */
	@Override
	public TrustInfo getTrustInfo(Certificate[][] untrustedChains, Collection<PGPPublicKey> untrustedKeys,
			String[] unsignedDetail) {
		return trust(untrustedChains, untrustedKeys, unsignedDetail);
	}

	private TrustInfo trust(Certificate[][] untrustedChains, Collection<PGPPublicKey> untrustedKeys,
			String[] unsignedDetail) {
		prompted = true;
		List<Certificate> certificates = new ArrayList<>();
		if (untrustedChains != null) {
			for (Certificate[] chain : untrustedChains) {
				if (chain != null && chain.length > 0) {
					// the leaf is what signed the artifact; trusting the whole chain
					// would accept everything the issuer ever signed
					certificates.add(chain[0]);
				}
			}
		}
		Set<PGPPublicKey> keys = new LinkedHashSet<>(untrustedKeys == null ? List.of() : untrustedKeys);
		synchronized (this) {
			if (unsignedDetail != null) {
				for (String detail : unsignedDetail) {
					prompts.add(trustUnsigned ? "unsigned: " + detail : "REFUSED, unsigned: " + detail);
				}
			}
			for (Certificate certificate : certificates) {
				prompts.add((trustUnsigned ? "certificate: " : "REFUSED, certificate: ") + describe(certificate));
			}
			for (PGPPublicKey key : keys) {
				prompts.add((trustUnsigned ? "PGP key: " : "REFUSED, PGP key: ")
						+ Long.toHexString(key.getKeyID()));
			}
		}
		if (!trustUnsigned) {
			// refuse everything, which is what the caller asked for
			return new TrustInfo(List.of(), List.of(), false, false);
		}
		// trust exactly what was presented, for this operation only. persistTrust
		// stays false so nothing reaches the IDE's permanent trust store, and
		// trustAlways is never returned because p2 writes that into a preference and
		// a switch flipped once is never flipped back
		return new TrustInfo(certificates, keys, false, true);
	}

	/** Enough of a certificate to recognise it in an answer. */
	private static String describe(Certificate certificate) {
		if (certificate instanceof java.security.cert.X509Certificate x509) {
			return String.valueOf(x509.getSubjectX500Principal());
		}
		return certificate.getType();
	}

	@Override
	public AuthenticationInfo getUsernamePassword(String location) {
		prompted = true;
		synchronized (this) {
			prompts.add("credentials for " + location);
		}
		return AUTHENTICATION_PROMPT_CANCELED;
	}

	@Override
	public AuthenticationInfo getUsernamePassword(String location, AuthenticationInfo previous) {
		return getUsernamePassword(location);
	}

	/** Installs this in place of the IDE's dialogs, returning what was there before. */
	static Object install(IProvisioningAgent agent, HeadlessTrust trust) {
		Object previous = agent.getService(UIServices.SERVICE_NAME);
		agent.registerService(UIServices.SERVICE_NAME, trust);
		return previous;
	}

	/** Puts the IDE's own dialogs back, so interactive updates still prompt. */
	static void restore(IProvisioningAgent agent, Object previous) {
		agent.unregisterService(UIServices.SERVICE_NAME, agent.getService(UIServices.SERVICE_NAME));
		if (previous != null) {
			agent.registerService(UIServices.SERVICE_NAME, previous);
		}
	}
}
