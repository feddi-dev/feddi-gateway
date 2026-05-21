const fs = require('fs');

const zeroCov = { covered: 0, missed: 0 };

function parseJacocoXml(jacocoFile) {
  const result = { overall: {}, classes: {} };

  if (!fs.existsSync(jacocoFile)) {
    return null;
  }

  const xml = fs.readFileSync(jacocoFile, 'utf8');

  const stripped = xml.replace(/<package[\s\S]*?<\/package>/g, '');
  const counterRe = /<counter type="(\w+)" missed="(\d+)" covered="(\d+)"\/>/g;
  let counterMatch;
  while ((counterMatch = counterRe.exec(stripped)) !== null) {
    const entry = {
      covered: parseInt(counterMatch[3], 10),
      missed: parseInt(counterMatch[2], 10),
    };
    if (counterMatch[1] === 'LINE') result.overall.line = entry;
    else if (counterMatch[1] === 'BRANCH') result.overall.branch = entry;
    else if (counterMatch[1] === 'METHOD') result.overall.method = entry;
  }

  const packageRe = /<package\s+name="([^"]+)">([\s\S]*?)<\/package>/g;
  let packageMatch;
  while ((packageMatch = packageRe.exec(xml)) !== null) {
    const classRe = /<class\s+name="([^"]+)"[^>]*(?<!\/)>([\s\S]*?)<\/class>/g;
    let classMatch;
    while ((classMatch = classRe.exec(packageMatch[2])) !== null) {
      const className = classMatch[1].replace(/\//g, '.');
      const classBody = classMatch[2];
      const counters = {
        line: { ...zeroCov },
        branch: { ...zeroCov },
        method: { ...zeroCov },
      };

      const classCounterRe = /<counter type="(\w+)" missed="(\d+)" covered="(\d+)"\/>/g;
      let classCounterMatch;
      while ((classCounterMatch = classCounterRe.exec(classBody)) !== null) {
        const entry = {
          covered: parseInt(classCounterMatch[3], 10),
          missed: parseInt(classCounterMatch[2], 10),
        };
        if (classCounterMatch[1] === 'LINE') counters.line = entry;
        else if (classCounterMatch[1] === 'BRANCH') counters.branch = entry;
        else if (classCounterMatch[1] === 'METHOD') counters.method = entry;
      }

      const methods = [];
      const methodRe = /<method\s+name="([^"]+)"\s+desc="([^"]+)"(?:\s+line="(\d+)")?[^>]*>([\s\S]*?)<\/method>/g;
      let methodMatch;
      while ((methodMatch = methodRe.exec(classBody)) !== null) {
        const methodCounters = {
          line: { ...zeroCov },
          branch: { ...zeroCov },
          method: { ...zeroCov },
        };
        const methodCounterRe = /<counter type="(\w+)" missed="(\d+)" covered="(\d+)"\/>/g;
        let methodCounterMatch;
        while ((methodCounterMatch = methodCounterRe.exec(methodMatch[4])) !== null) {
          const entry = {
            covered: parseInt(methodCounterMatch[3], 10),
            missed: parseInt(methodCounterMatch[2], 10),
          };
          if (methodCounterMatch[1] === 'LINE') methodCounters.line = entry;
          else if (methodCounterMatch[1] === 'BRANCH') methodCounters.branch = entry;
          else if (methodCounterMatch[1] === 'METHOD') methodCounters.method = entry;
        }

        if (methodCounters.line.covered + methodCounters.line.missed > 0) {
          methods.push({
            name: methodMatch[1],
            desc: methodMatch[2],
            line: methodMatch[3] ? parseInt(methodMatch[3], 10) : null,
            counters: methodCounters,
          });
        }
      }

      if (counters.line.covered + counters.line.missed > 0) {
        result.classes[className] = counters;
        if (methods.length > 0) {
          result.classes[className].methods = methods;
        }
      }
    }
  }

  return result;
}

function pct(covered, missed) {
  const total = covered + missed;
  return total === 0 ? 0 : (covered / total) * 100;
}

function counterPct(counter) {
  return pct(counter?.covered || 0, counter?.missed || 0);
}

function isRegression(currPct, basePct, currMissed, baseMissed, tolerance = 0.05) {
  return currPct < basePct - tolerance && currMissed > baseMissed;
}

module.exports = {
  parseJacocoXml,
  pct,
  counterPct,
  zeroCov,
  isRegression,
};
