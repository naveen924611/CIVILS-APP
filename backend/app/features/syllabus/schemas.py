from pydantic import BaseModel, Field


class NodeOut(BaseModel):
    """One node the AI reads out of the syllabus text."""

    title: str
    exam_tags: list[str] = Field(default_factory=list)
    est_hours: float | None = None
    children: list["NodeOut"] = Field(default_factory=list)


NodeOut.model_rebuild()


class ChunkOut(BaseModel):
    nodes: list[NodeOut] = Field(default_factory=list)


class ImportIn(BaseModel):
    exam: str = Field(min_length=2, max_length=80)
    title: str = Field(min_length=2, max_length=200)
    text: str | None = None
    document_id: str | None = None


class ApproveIn(BaseModel):
    exam_filter: str | None = None
    merge_into_existing: bool = True
    tree: list[dict] | None = None  # the tree as edited on the tablet (optional)


class TreeIn(BaseModel):
    tree: list[dict]
    title: str | None = None
