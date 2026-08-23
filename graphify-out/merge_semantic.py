import json
from pathlib import Path

# Load new chunk
chunk_path = Path('graphify-out/.graphify_chunk_01.json')
if chunk_path.exists():
    new = json.loads(chunk_path.read_text(encoding='utf-8'))
else:
    new = {'nodes': [], 'edges': [], 'hyperedges': []}

# Load cached if exists
cached_path = Path('graphify-out/.graphify_cached.json')
if cached_path.exists():
    cached = json.loads(cached_path.read_text(encoding='utf-8'))
else:
    cached = {'nodes': [], 'edges': [], 'hyperedges': []}

# Merge
all_nodes = cached['nodes'] + new.get('nodes', [])
all_edges = cached['edges'] + new.get('edges', [])
all_hyperedges = cached.get('hyperedges', []) + new.get('hyperedges', [])

# Dedupe nodes by id
seen = set()
deduped = []
for n in all_nodes:
    if n['id'] not in seen:
        seen.add(n['id'])
        deduped.append(n)

merged = {
    'nodes': deduped,
    'edges': all_edges,
    'hyperedges': all_hyperedges,
    'input_tokens': new.get('input_tokens', 0),
    'output_tokens': new.get('output_tokens', 0),
}
Path('graphify-out/.graphify_semantic.json').write_text(json.dumps(merged, indent=2), encoding='utf-8')
print(f'Extraction complete - {len(deduped)} nodes, {len(all_edges)} edges ({len(cached["nodes"])} from cache, {len(new.get("nodes",[]))} new)')
