#!/usr/bin/env node

const mediaUrl = process.argv[2]

if (!mediaUrl) {
  console.error('用法: node scripts/check-media-delivery.js <视频公网 URL>')
  process.exit(2)
}

function inspectTopLevelMp4Boxes(buffer) {
  const boxes = []
  let offset = 0

  while (offset + 8 <= buffer.length) {
    let size = buffer.readUInt32BE(offset)
    const type = buffer.toString('ascii', offset + 4, offset + 8)
    let headerSize = 8

    if (size === 1 && offset + 16 <= buffer.length) {
      const extendedSize = buffer.readBigUInt64BE(offset + 8)
      if (extendedSize > BigInt(Number.MAX_SAFE_INTEGER)) break
      size = Number(extendedSize)
      headerSize = 16
    } else if (size === 0) {
      size = buffer.length - offset
    }

    if (size < headerSize) break
    boxes.push({ type, offset, size })
    if (offset + size > buffer.length) break
    offset += size
  }

  return boxes
}

async function main() {
  const response = await fetch(mediaUrl, {
    headers: { Range: 'bytes=0-2097151' },
    redirect: 'follow'
  })
  const acceptRanges = response.headers.get('accept-ranges') || ''
  const contentRange = response.headers.get('content-range') || ''
  const body = Buffer.from(await response.arrayBuffer())

  console.log(`Status: ${response.status}`)
  console.log(`Accept-Ranges: ${acceptRanges || '(missing)'}`)
  console.log(`Content-Range: ${contentRange || '(missing)'}`)
  console.log(`Initial range bytes: ${body.length}`)

  let failed = false
  if (response.status !== 206) {
    console.error('FAIL: 服务端没有返回 206 Partial Content')
    failed = true
  }
  if (acceptRanges.toLowerCase() !== 'bytes') {
    console.error('FAIL: 响应缺少 Accept-Ranges: bytes')
    failed = true
  }
  if (!contentRange.toLowerCase().startsWith('bytes ')) {
    console.error('FAIL: 响应缺少有效的 Content-Range')
    failed = true
  }

  const boxes = inspectTopLevelMp4Boxes(body)
  const moov = boxes.find(box => box.type === 'moov')
  const mdat = boxes.find(box => box.type === 'mdat')
  console.log(`MP4 boxes: ${boxes.map(box => box.type).join(' -> ') || '(not detected)'}`)

  if (!moov || (mdat && moov.offset > mdat.offset)) {
    console.error('FAIL: 前 2MB 未检测到位于 mdat 之前的 moov，faststart 可能未生效')
    failed = true
  } else {
    console.log(`PASS: moov 位于文件前部（offset=${moov.offset}）`)
  }

  if (failed) process.exitCode = 1
}

main().catch(error => {
  console.error(`检查失败: ${error instanceof Error ? error.message : String(error)}`)
  process.exitCode = 1
})
