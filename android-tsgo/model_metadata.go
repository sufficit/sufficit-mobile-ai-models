package tsgo

import (
	"path/filepath"
	"strings"
)

func embeddingModelIDFromPath(modelPath string) string {
	base := strings.TrimSuffix(filepath.Base(modelPath), filepath.Ext(modelPath))
	if base == "" || base == "." {
		return ""
	}
	return strings.ToLower(base) + "-embedding"
}

func transcriptionModelIDFromPath(modelPath string) string {
	base := strings.TrimSuffix(filepath.Base(modelPath), filepath.Ext(modelPath))
	if base == "" || base == "." {
		return ""
	}
	return base
}

func modelMatches(requested, active string) bool {
	requested = strings.TrimSpace(requested)
	return requested == "" || strings.EqualFold(requested, strings.TrimSpace(active))
}
