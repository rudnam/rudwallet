package wallet

import (
	"bytes"
	"embed"
	"encoding/csv"
	"fmt"
	"strconv"
	"strings"
	"sync"
)

// The station and bus company names a transit IC card's codes stand for
// (icstations/README.md says where they come from).
//
//go:embed icstations/station_codes.csv icstations/bus_company_codes.csv
var icCodeFiles embed.FS

// ICStation is where a fare started or ended. Name is empty when the
// station isn't in the list; Code is the card's region, line and station,
// e.g. 0-25-0A, either way.
type ICStation struct {
	Code    string `json:"code"`
	Company string `json:"company,omitempty"`
	Line    string `json:"line,omitempty"`
	Name    string `json:"name,omitempty"`
}

// icStationCode is a station's region (0-3), line and station codes.
type icStationCode [3]byte

func (c icStationCode) String() string { return fmt.Sprintf("%X-%02X-%02X", c[0], c[1], c[2]) }

// icStations is the station list by code. Of two rows with one code, the
// later wins, as in the list's own app.
var icStations = sync.OnceValue(func() map[icStationCode]ICStation {
	out := map[icStationCode]ICStation{}
	for _, rec := range icCodeRows("icstations/station_codes.csv", 6) {
		var code icStationCode
		ok := true
		for i := range 3 {
			n, err := strconv.ParseUint(rec[i], 16, 8)
			ok = ok && err == nil
			code[i] = byte(n)
		}
		if ok && code[0] < 4 {
			out[code] = ICStation{Code: code.String(), Company: rec[3], Line: rec[4], Name: rec[5]}
		}
	}
	return out
})

// icBusCompanies is the bus companies by code.
var icBusCompanies = sync.OnceValue(func() map[uint16]string {
	out := map[uint16]string{}
	for _, rec := range icCodeRows("icstations/bus_company_codes.csv", 2) {
		if n, err := strconv.ParseUint(rec[0], 16, 16); err == nil && rec[1] != "" {
			out[uint16(n)] = rec[1]
		}
	}
	return out
})

// icCodeRows reads an embedded list, without its header, keeping rows with
// at least n fields, trimmed.
func icCodeRows(name string, n int) [][]string {
	data, err := icCodeFiles.ReadFile(name)
	if err != nil {
		panic(err) // embedded: can't happen
	}
	r := csv.NewReader(bytes.NewReader(data))
	r.FieldsPerRecord = -1
	all, err := r.ReadAll()
	if err != nil {
		panic(fmt.Sprintf("%s: %v", name, err))
	}
	var rows [][]string
	for _, rec := range all[1:] {
		if len(rec) < n {
			continue
		}
		for i := range rec {
			rec[i] = strings.TrimSpace(rec[i])
		}
		rows = append(rows, rec)
	}
	return rows
}

// icStationAt names the station with the code; one not in the list has
// only its code.
func icStationAt(code icStationCode) ICStation {
	if s, ok := icStations()[code]; ok {
		return s
	}
	return ICStation{Code: code.String()}
}
