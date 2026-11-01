// Lets Chrome treat the page as an installable app. Nothing is cached: the remote
// shows the projector's live state, so every request goes to the network as usual.
self.addEventListener("install", () => self.skipWaiting());
self.addEventListener("activate", (event) => event.waitUntil(self.clients.claim()));
self.addEventListener("fetch", () => {});
