package main

import (
	"fmt"
	"net/http"
	"os"
	"time"
)

func main() {
	url := os.Getenv("DAYMARK_HEALTHCHECK_URL")
	if url == "" {
		// Literal IP, not "localhost": distroless has no /etc/nsswitch.conf and Go's
		// resolver behaviour on a name is an avoidable variable.
		//
		// /readyz, not /healthz. /healthz answers "the process is up", which this probe
		// has already established by getting a TCP connection at all — it could not fail
		// for any reason /readyz would not also catch. /readyz additionally proves the
		// data volume is writable, which is how this server actually breaks: a read-only
		// mount, a volume owned by the wrong UID, a full disk. Docker never restarts a
		// container for being unhealthy on a single host, so the health status is a
		// signal for a human, and this is the one worth showing them.
		//
		// Set DAYMARK_HEALTHCHECK_URL to .../healthz for liveness-only semantics.
		url = "http://127.0.0.1:8080/readyz"
	}
	resp, err := (&http.Client{Timeout: 2 * time.Second}).Get(url)
	if err != nil {
		fmt.Fprintln(os.Stderr, "healthcheck:", err)
		os.Exit(1)
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		fmt.Fprintf(os.Stderr, "healthcheck: status %d\n", resp.StatusCode)
		os.Exit(1)
	}
}
