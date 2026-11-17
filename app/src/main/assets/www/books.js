/* Book names, chapter counts and the Scripture-reference parser. */
(function (global) {
  'use strict';
  // [name, short, chapters, aliases...]
  const BOOKS = [
    ['Genesis', 'Gen', 50, 'ge', 'gn', 'gen'], ['Exodus', 'Exo', 40, 'ex', 'exod', 'exo'],
    ['Leviticus', 'Lev', 27, 'lv', 'lev'], ['Numbers', 'Num', 36, 'nu', 'nm', 'nb', 'numb', 'num'],
    ['Deuteronomy', 'Deu', 34, 'dt', 'deut', 'deu'], ['Joshua', 'Jos', 24, 'josh', 'jsh', 'jos'],
    ['Judges', 'Jdg', 21, 'judg', 'jdgs', 'jg', 'jdg'], ['Ruth', 'Rut', 4, 'ru', 'rth', 'rut'],
    ['1 Samuel', '1Sa', 31], ['2 Samuel', '2Sa', 24], ['1 Kings', '1Ki', 22], ['2 Kings', '2Ki', 25],
    ['1 Chronicles', '1Ch', 29], ['2 Chronicles', '2Ch', 36], ['Ezra', 'Ezr', 10, 'ezr'],
    ['Nehemiah', 'Neh', 13, 'neh'], ['Esther', 'Est', 10, 'esth', 'est'], ['Job', 'Job', 42, 'jb'],
    ['Psalms', 'Psa', 150, 'ps', 'psa', 'psalm', 'pss', 'psm'], ['Proverbs', 'Pro', 31, 'pr', 'prov', 'prv', 'pro'],
    ['Ecclesiastes', 'Ecc', 12, 'ec', 'eccl', 'eccles', 'qoh', 'ecc'],
    ['Song of Solomon', 'Son', 8, 'song', 'sos', 'sng', 'song of songs', 'canticles', 'cant', 'son'],
    ['Isaiah', 'Isa', 66, 'isa'], ['Jeremiah', 'Jer', 52, 'jr', 'jer'], ['Lamentations', 'Lam', 5, 'lam'],
    ['Ezekiel', 'Eze', 48, 'ezek', 'ezk', 'eze'], ['Daniel', 'Dan', 12, 'dn', 'dan'], ['Hosea', 'Hos', 14, 'hos'],
    ['Joel', 'Joe', 3, 'jl', 'joe'], ['Amos', 'Amo', 9, 'amo'], ['Obadiah', 'Oba', 1, 'obad', 'oba'],
    ['Jonah', 'Jon', 4, 'jnh', 'jon'], ['Micah', 'Mic', 7, 'mic'], ['Nahum', 'Nah', 3, 'nah'],
    ['Habakkuk', 'Hab', 3, 'hab'], ['Zephaniah', 'Zep', 3, 'zeph', 'zp', 'zep'], ['Haggai', 'Hag', 2, 'hg', 'hag'],
    ['Zechariah', 'Zec', 14, 'zech', 'zc', 'zec'], ['Malachi', 'Mal', 4, 'ml', 'mal'],
    ['Matthew', 'Mat', 28, 'mt', 'matt', 'mat'], ['Mark', 'Mar', 16, 'mk', 'mrk', 'mr', 'mar'],
    ['Luke', 'Luk', 24, 'lk', 'lu', 'luk'], ['John', 'Joh', 21, 'jn', 'jhn', 'joh'], ['Acts', 'Act', 28, 'act'],
    ['Romans', 'Rom', 16, 'rm', 'ro', 'rom'], ['1 Corinthians', '1Co', 16], ['2 Corinthians', '2Co', 13],
    ['Galatians', 'Gal', 6, 'ga', 'gal'], ['Ephesians', 'Eph', 6, 'ephes', 'eph'],
    ['Philippians', 'Php', 4, 'phil', 'phl', 'php'], ['Colossians', 'Col', 4, 'col'],
    ['1 Thessalonians', '1Th', 5], ['2 Thessalonians', '2Th', 3], ['1 Timothy', '1Ti', 6], ['2 Timothy', '2Ti', 4],
    ['Titus', 'Tit', 3, 'tit'], ['Philemon', 'Phm', 1, 'philem', 'phlm', 'phm'], ['Hebrews', 'Heb', 13, 'heb'],
    ['James', 'Jas', 5, 'jm', 'jam', 'jas'], ['1 Peter', '1Pe', 5], ['2 Peter', '2Pe', 3],
    ['1 John', '1Jn', 5], ['2 John', '2Jn', 1], ['3 John', '3Jn', 1], ['Jude', 'Jud', 1, 'jde', 'jud'],
    ['Revelation', 'Rev', 22, 'rv', 'revelations', 'apocalypse', 'rev']
  ];
  // base aliases for numbered books (without the number)
  const NUMBERED = {
    9: ['samuel', 'sam', 'sa', 'sm'], 11: ['kings', 'kgs', 'ki', 'kin', 'kg'],
    13: ['chronicles', 'chron', 'chr', 'ch'], 46: ['corinthians', 'cor', 'co'],
    52: ['thessalonians', 'thess', 'thes', 'th'], 54: ['timothy', 'tim', 'ti', 'tm'],
    60: ['peter', 'pet', 'pe', 'pt'], 62: ['john', 'joh', 'jn', 'jo', 'jhn']
  };
  const PREFIX = { 1: ['1', 'i', '1st', 'first'], 2: ['2', 'ii', '2nd', 'second'], 3: ['3', 'iii', '3rd', 'third'] };

  const alias = new Map(); // normalised alias -> book number
  const norm = s => s.toLowerCase().replace(/[.\s_]+/g, ' ').trim();
  BOOKS.forEach((b, i) => {
    const n = i + 1;
    [b[0], b[1], ...b.slice(3)].forEach(a => alias.set(norm(a), n));
  });
  Object.keys(NUMBERED).forEach(k => {
    const first = +k;
    const count = first === 62 ? 3 : 2;
    for (let j = 0; j < count; j++) {
      PREFIX[j + 1].forEach(p => NUMBERED[k].forEach(base => {
        alias.set(norm(p + ' ' + base), first + j);
        alias.set(norm(p + base), first + j);
      }));
    }
  });
  const esc = s => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const names = [...alias.keys()].sort((a, b) => b.length - a.length)
    .map(a => esc(a).replace(/ /g, '[\\s.]*'));
  // e.g. "Rom. 5:8-10", "Joh_3:16", "1 Cor 13:4", "Ps 23:1-3"
  const REF_RE = new RegExp('\\b(' + names.join('|') + ')\\.?[\\s_]*(\\d{1,3}):(\\d{1,3})(?:\\s*[-–]\\s*(\\d{1,3})(?::(\\d{1,3}))?)?(?![\\d:])', 'gi');
  const CONT_RE = /^(\s*[;,]\s*)(?:(\d{1,3}):)?(\d{1,3})(?:\s*[-–]\s*(\d{1,3}))?(?![\d:])/;

  function bookFromName(s) {
    return alias.get(norm(s)) || 0;
  }

  /** Finds Scripture references in plain text. Returns [{start, end, b, c, v, v2, c2}] */
  function findRefs(text) {
    const out = [];
    REF_RE.lastIndex = 0;
    let m;
    while ((m = REF_RE.exec(text))) {
      const b = bookFromName(m[1]);
      if (!b) continue;
      let c = +m[2], v = +m[3], c2 = c, v2 = v;
      if (m[4]) { if (m[5]) { c2 = +m[4]; v2 = +m[5]; } else v2 = +m[4]; }
      if (c < 1 || c > BOOKS[b - 1][2]) continue;
      out.push({ start: m.index, end: m.index + m[0].length, b, c, v, c2, v2 });
      // continuation: "Rom. 3:9; 11:32, 34"
      let pos = REF_RE.lastIndex, curC = c2, k;
      while ((k = CONT_RE.exec(text.slice(pos)))) {
        const sepLen = k[1].length;
        let cc = k[2] ? +k[2] : curC, vv = +k[3], vv2 = k[4] ? +k[4] : vv;
        if (!k[2] && k[1].trim() === ';') break; // "; 4" is ambiguous - stop
        out.push({ start: pos + sepLen, end: pos + k[0].length, b, c: cc, v: vv, c2: cc, v2: vv2 });
        curC = cc;
        pos += k[0].length;
      }
      REF_RE.lastIndex = pos;
    }
    return out;
  }

  /** Parses a typed reference like "jn 3:16", "Gen 1", "1 cor 13". */
  function parseRef(s) {
    const m = /^\s*((?:[123]|i{1,3})?\s*[a-z][a-z.\s]*?)\.?\s*(\d{1,3})?(?:\s*[:.\s]\s*(\d{1,3}))?\s*$/i.exec(s || '');
    if (!m) return null;
    const b = bookFromName(m[1]);
    if (!b) return null;
    return { b, c: m[2] ? Math.min(+m[2], BOOKS[b - 1][2]) : 1, v: m[3] ? +m[3] : 0 };
  }

  function refText(b, c, v, v2, c2) {
    let s = BOOKS[b - 1][0] + ' ' + c;
    if (v) s += ':' + v;
    if (c2 && c2 !== c) s += '–' + c2 + ':' + v2;
    else if (v2 && v2 !== v) s += '–' + v2;
    return s;
  }

  global.Books = {
    list: BOOKS.map((b, i) => ({ n: i + 1, name: b[0], short: b[1], chapters: b[2] })),
    name: b => (BOOKS[b - 1] || ['?'])[0],
    short: b => (BOOKS[b - 1] || ['?', '?'])[1],
    chapters: b => (BOOKS[b - 1] || [0, 0, 0])[2],
    bookFromName, findRefs, parseRef, refText
  };
})(window);
