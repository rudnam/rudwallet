// Cloudflare Email Worker: hands every email sent to the inbound domain to
// the app's webhook. Set up in docs/email.md. Variables (Settings → Variables):
//   APP_URL         the app's public address, e.g. https://kakeibo.example.com
//   INBOUND_SECRET  the same value as the app's INBOUND_SECRET (as a secret)
export default {
  async email(message, env) {
    const raw = await new Response(message.raw).arrayBuffer();
    const res = await fetch(`${env.APP_URL}/api/inbound/email`, {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${env.INBOUND_SECRET}`,
        'Content-Type': 'message/rfc822',
        // Forwarded mail keeps its original To: header; this is who it was sent to now.
        'X-Inbound-To': message.to,
      },
      body: raw,
    });
    if (res.status === 404) {
      message.setReject('Unknown address');
    } else if (!res.ok) {
      // Throwing makes Cloudflare report the failure; the sender's server retries.
      throw new Error(`app answered ${res.status}: ${await res.text()}`);
    }
  },
};
