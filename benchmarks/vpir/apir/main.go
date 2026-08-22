package main

import (
	"encoding/csv"
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/si-co/vpir-code/lib/client"
	"github.com/si-co/vpir-code/lib/database"
	"github.com/si-co/vpir-code/lib/server"
	"github.com/si-co/vpir-code/lib/utils"
)

type metrics struct {
	query            time.Duration
	answer           time.Duration
	reconstruct      time.Duration
	queryBytes       uint64
	responseBytes    uint64
	tamperRejected   bool
	observedFailures uint64
}

func positiveArg(index int, name string) uint64 {
	value, err := strconv.ParseUint(os.Args[index], 10, 64)
	if err != nil || value == 0 {
		panic(fmt.Sprintf("%s must be a positive integer", name))
	}
	return value
}

func nonnegativeArg(index int, name string) uint64 {
	value, err := strconv.ParseUint(os.Args[index], 10, 64)
	if err != nil {
		panic(fmt.Sprintf("%s must be a nonnegative integer", name))
	}
	return value
}

func retrieveRecord(c *client.LWE128, s *server.LWE128, db *database.LWE128,
	record int, bitsPerRecord int, measured bool, out *metrics) {
	for bit := 0; bit < bitsPerRecord; bit++ {
		index := record*bitsPerRecord + bit
		start := time.Now()
		query, err := c.QueryBytes(index)
		if err != nil {
			panic(err)
		}
		afterQuery := time.Now()
		answer, err := s.AnswerBytes(query)
		if err != nil {
			panic(err)
		}
		afterAnswer := time.Now()
		result, err := c.ReconstructBytes(answer)
		afterReconstruct := time.Now()
		if err != nil {
			out.observedFailures++
		} else {
			i, j := utils.VectorToMatrixIndices(index, db.Info.NumColumns)
			if result != uint32(db.Matrix.Get(i, j)) {
				out.observedFailures++
			}
		}
		if measured {
			out.query += afterQuery.Sub(start)
			out.answer += afterAnswer.Sub(afterQuery)
			out.reconstruct += afterReconstruct.Sub(afterAnswer)
			out.queryBytes += uint64(len(query))
			out.responseBytes += uint64(len(answer))
		}
	}
}

func main() {
	if len(os.Args) != 5 {
		fmt.Fprintln(os.Stderr, "usage: apir-bench RECORDS VALUE_BYTES WARMUPS QUERIES")
		os.Exit(2)
	}
	records := positiveArg(1, "records")
	valueBytes := positiveArg(2, "value_bytes")
	warmups := nonnegativeArg(3, "warmups")
	queries := positiveArg(4, "queries")
	bitsPerRecord := int(valueBytes * 8)
	databaseBits := int(records) * bitsPerRecord

	setupStart := time.Now()
	db := database.CreateRandomBinaryLWEWithLength128(utils.RandomPRG(), databaseBits)
	params := utils.ParamsWithDatabaseSize128(db.Info.NumRows, db.Info.NumColumns)
	c := client.NewLWE128(utils.RandomPRG(), &db.Info, params)
	s := server.NewLWE128(db)
	preprocess := time.Since(setupStart)

	var out metrics
	for i := uint64(0); i < warmups; i++ {
		retrieveRecord(c, s, db, int(i%records), bitsPerRecord, false, &out)
	}
	for i := uint64(0); i < queries; i++ {
		record := int((i*7919 + 17) % records)
		retrieveRecord(c, s, db, record, bitsPerRecord, true, &out)
	}

	tamperIndex := 17 % databaseBits
	tamperQuery, err := c.QueryBytes(tamperIndex)
	if err != nil {
		panic(err)
	}
	tamperAnswer, err := s.AnswerBytes(tamperQuery)
	if err != nil {
		panic(err)
	}
	for i := 8; i < len(tamperAnswer) && i < 24; i++ {
		tamperAnswer[i] ^= 0xff
	}
	_, err = c.ReconstructBytes(tamperAnswer)
	out.tamperRejected = err != nil

	divisor := time.Duration(queries)
	queryMs := float64(out.query/divisor) / float64(time.Millisecond)
	answerMs := float64(out.answer/divisor) / float64(time.Millisecond)
	reconstructMs := float64(out.reconstruct/divisor) / float64(time.Millisecond)
	queryBytes := out.queryBytes / queries
	responseBytes := out.responseBytes / queries
	digestBytes := uint64(8 + db.Info.DigestLWE128.Rows()*db.Info.DigestLWE128.Cols()*16)
	serverBytes := uint64(db.Info.NumRows * db.Info.NumColumns)

	writer := csv.NewWriter(os.Stdout)
	_ = writer.Write([]string{
		"scheme", "variant", "records", "value_bytes", "logical_database_bytes",
		"native_queries_per_retrieval", "warmup_retrievals", "measured_retrievals",
		"preprocess_ms", "query_ms", "answer_ms", "verify_recover_ms", "total_online_ms",
		"query_bytes", "response_bytes", "client_state_bytes", "server_state_bytes",
		"tamper_rejected", "observed_failures",
	})
	_ = writer.Write([]string{
		"AuthenticatedPIR", "LWE128-single-server", strconv.FormatUint(records, 10),
		strconv.FormatUint(valueBytes, 10), strconv.FormatUint(records*valueBytes, 10),
		strconv.Itoa(bitsPerRecord), strconv.FormatUint(warmups, 10), strconv.FormatUint(queries, 10),
		fmt.Sprintf("%.12g", float64(preprocess)/float64(time.Millisecond)),
		fmt.Sprintf("%.12g", queryMs), fmt.Sprintf("%.12g", answerMs),
		fmt.Sprintf("%.12g", reconstructMs), fmt.Sprintf("%.12g", queryMs+answerMs+reconstructMs),
		strconv.FormatUint(queryBytes, 10), strconv.FormatUint(responseBytes, 10),
		strconv.FormatUint(digestBytes, 10), strconv.FormatUint(serverBytes, 10),
		strconv.FormatBool(out.tamperRejected), strconv.FormatUint(out.observedFailures, 10),
	})
	writer.Flush()
	if err := writer.Error(); err != nil {
		panic(err)
	}
	if out.observedFailures != 0 || !out.tamperRejected {
		os.Exit(1)
	}
}
