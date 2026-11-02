# Transit IC card codes

What the IC card import (`../importic.go`) names a card's entries with:

- `station_codes.csv`: each station by region (地区コード), line (線区コード)
  and station (駅順コード), in hex, with its company, line and name.
- `bus_company_codes.csv`: bus companies by their code (事業者コード, hex).

Both are copied unchanged from [soltia48/suica-viewer](https://github.com/soltia48/suica-viewer)
(`assets/`, commit `e9656e64d61a71ed4b9eac0bd6cfb283db716e4d`, October 2026),
under its MIT licence (`LICENSE`). The codes themselves aren't published by
the railways; lists like this one were worked out by reading cards, so a
station may be missing or wrong. To update, copy the files again from a newer
commit and note it here.
