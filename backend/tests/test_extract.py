import httpx

from app.pipelines.news.extract import fetch_article_text
from app.pipelines.news.feeds import Robots

PAGE = "<html><body><article><h1>Title</h1>" + "".join(f"<p>Paragraph {i} about the Union Budget and fiscal policy in India today.</p>" for i in range(12)) + "</article></body></html>"


def test_fetch_article_text_reads_page_and_respects_robots():
    def handler(req):
        if req.url.path == "/robots.txt":
            return httpx.Response(200, text="User-agent: *\nDisallow: /secret")
        if req.url.path == "/gone":
            return httpx.Response(404)
        return httpx.Response(200, text=PAGE)

    c = httpx.Client(transport=httpx.MockTransport(handler))
    robots = Robots(c)
    assert "Union Budget" in fetch_article_text(c, robots, "https://s.test/story")
    assert fetch_article_text(c, robots, "https://s.test/secret/x") is None
    assert fetch_article_text(c, robots, "https://s.test/gone") is None
