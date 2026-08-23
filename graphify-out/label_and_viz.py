import sys, json
from graphify.build import build_from_json
from graphify.cluster import score_all
from graphify.analyze import god_nodes, surprising_connections, suggest_questions
from graphify.report import generate
from graphify.export import to_json, to_html
from pathlib import Path

extraction = json.loads(Path('graphify-out/.graphify_extract.json').read_text(encoding='utf-8'))
detection = json.loads(Path('graphify-out/.graphify_detect.json').read_text(encoding='utf-8'))
analysis = json.loads(Path('graphify-out/.graphify_analysis.json').read_text(encoding='utf-8'))

G = build_from_json(extraction)
communities = {int(k): v for k, v in analysis['communities'].items()}
cohesion = {int(k): v for k, v in analysis['cohesion'].items()}
tokens = {'input': extraction.get('input_tokens', 0), 'output': extraction.get('output_tokens', 0)}

# Community labels based on node analysis
labels = {
    0: "Core Trading System",
    1: "Broker Adapters",
    2: "Trading Models",
    3: "IronFly Adjustment",
    4: "Broker Registry",
    5: "Market Data & Risk",
    6: "Backtest Runner",
    7: "Agent UI",
    8: "Market Infrastructure",
    9: "Broker Isolation Tests",
    10: "Black-Scholes Pricer",
    11: "Strategy Engine",
    12: "Intraday Trend Strategy",
    13: "Database Services",
    14: "Technical Indicators",
}

# Regenerate questions with real community labels
questions = suggest_questions(G, communities, labels)

report = generate(G, communities, cohesion, labels, analysis['gods'], analysis['surprises'], detection, tokens, 'D:\\code\\trading-bot', suggested_questions=questions)
Path('graphify-out/GRAPH_REPORT.md').write_text(report, encoding='utf-8')
Path('graphify-out/.graphify_labels.json').write_text(json.dumps({str(k): v for k, v in labels.items()}), encoding='utf-8')

# Generate HTML
if G.number_of_nodes() <= 5000:
    to_html(G, communities, 'graphify-out/graph.html', community_labels=labels)
    print('graph.html written')
else:
    print(f'Graph has {G.number_of_nodes()} nodes - too large for HTML viz')

print('Report updated with community labels')
