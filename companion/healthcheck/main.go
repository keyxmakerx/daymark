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
		url = "http://127.0.0.1:8080/healthz"
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
