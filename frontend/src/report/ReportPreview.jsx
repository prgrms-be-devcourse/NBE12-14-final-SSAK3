import React, { useMemo, useState } from 'react'
import { ReportPage } from './ReportPage.jsx'
import { createPreviewSource, previewOptions, sampleRoom } from './preview.js'

function Preview({ kind }) {
  const source = useMemo(() => createPreviewSource(kind), [kind])
  return <ReportPage source={source} room={sampleRoom} />
}
export function ReportPreview() {
  const [kind, setKind] = useState('normal')
  return <div className="report-app">
    <aside className="report-preview-bar" aria-label="샘플 미리보기 설정">
      <div><strong>샘플 데이터 미리보기</strong><p>발언·질문·투표는 가상 데이터입니다. 실제 생성 요청이나 LLM 호출은 없습니다.</p></div>
      <label>시나리오<select value={kind} onChange={event => setKind(event.target.value)}>
        {Object.entries(previewOptions).map(([value, label]) => <option value={value} key={value}>{label}</option>)}
      </select></label>
    </aside>
    <Preview key={kind} kind={kind} />
  </div>
}
