import asyncio
import hashlib
import io
import os
import uuid
from dataclasses import dataclass
from pathlib import Path

from PIL import Image, ImageOps, UnidentifiedImageError


class InvalidSafetyImage(ValueError):
    pass


@dataclass(frozen=True, slots=True)
class PreparedSafetyImage:
    content_type: str
    uploaded_byte_size: int
    content_sha256: str
    original_bytes: bytes
    thumbnail_bytes: bytes
    extension: str


@dataclass(frozen=True, slots=True)
class StoredSafetyImage:
    original_storage_name: str
    thumbnail_storage_name: str


class SafetyImageStorage:
    def __init__(self, root: str, thumbnail_max_pixels: int) -> None:
        self.root = Path(root).resolve()
        self.originals = self.root / "originals"
        self.thumbnails = self.root / "thumbnails"
        self.thumbnail_max_pixels = thumbnail_max_pixels

    async def initialize(self) -> None:
        await asyncio.to_thread(self._initialize_sync)

    async def prepare(self, data: bytes, declared_content_type: str) -> PreparedSafetyImage:
        return await asyncio.to_thread(self._prepare_sync, data, declared_content_type)

    async def save(self, image: PreparedSafetyImage) -> StoredSafetyImage:
        return await asyncio.to_thread(self._save_sync, image)

    async def read(self, storage_name: str, *, thumbnail: bool) -> bytes:
        directory = self.thumbnails if thumbnail else self.originals
        path = self._safe_path(directory, storage_name)
        return await asyncio.to_thread(path.read_bytes)

    async def delete(self, original_storage_name: str, thumbnail_storage_name: str) -> None:
        await asyncio.to_thread(
            self._delete_sync,
            original_storage_name,
            thumbnail_storage_name,
        )

    def _initialize_sync(self) -> None:
        for directory in (self.root, self.originals, self.thumbnails):
            directory.mkdir(parents=True, exist_ok=True)
            try:
                directory.chmod(0o700)
            except OSError:
                pass

    def _prepare_sync(self, data: bytes, declared_content_type: str) -> PreparedSafetyImage:
        detected_content_type, extension = self._detect_signature(data)
        if detected_content_type != declared_content_type:
            raise InvalidSafetyImage("declared image type does not match the file signature")
        try:
            with Image.open(io.BytesIO(data)) as source:
                if source.format not in {"JPEG", "PNG"}:
                    raise InvalidSafetyImage("image format is not supported")
                if getattr(source, "n_frames", 1) != 1:
                    raise InvalidSafetyImage("animated images are not supported")
                if source.width * source.height > 40_000_000:
                    raise InvalidSafetyImage("image dimensions are too large")
                source.load()
                normalized = ImageOps.exif_transpose(source)
                normalized = self._normalize_mode(normalized, detected_content_type)
                original_bytes = self._encode(normalized, detected_content_type)
                thumbnail = normalized.copy()
                thumbnail.thumbnail(
                    (self.thumbnail_max_pixels, self.thumbnail_max_pixels),
                    Image.Resampling.LANCZOS,
                )
                thumbnail_bytes = self._encode(thumbnail, detected_content_type)
        except (Image.DecompressionBombError, UnidentifiedImageError, OSError) as exc:
            raise InvalidSafetyImage("image bytes cannot be decoded safely") from exc
        return PreparedSafetyImage(
            content_type=detected_content_type,
            uploaded_byte_size=len(data),
            content_sha256=hashlib.sha256(data).hexdigest(),
            original_bytes=original_bytes,
            thumbnail_bytes=thumbnail_bytes,
            extension=extension,
        )

    @staticmethod
    def _detect_signature(data: bytes) -> tuple[str, str]:
        if data.startswith(b"\xff\xd8\xff"):
            return "image/jpeg", ".jpg"
        if data.startswith(b"\x89PNG\r\n\x1a\n"):
            return "image/png", ".png"
        raise InvalidSafetyImage("image signature is not JPEG or PNG")

    @staticmethod
    def _normalize_mode(image: Image.Image, content_type: str) -> Image.Image:
        if content_type == "image/jpeg":
            if image.mode in {"RGBA", "LA"}:
                background = Image.new("RGB", image.size, "white")
                alpha = image.getchannel("A")
                background.paste(image.convert("RGB"), mask=alpha)
                return background
            return image.convert("RGB")
        if image.mode not in {"L", "LA", "RGB", "RGBA"}:
            return image.convert("RGBA")
        return image.copy()

    @staticmethod
    def _encode(image: Image.Image, content_type: str) -> bytes:
        output = io.BytesIO()
        if content_type == "image/jpeg":
            image.save(output, format="JPEG", quality=92, optimize=True)
        else:
            image.save(output, format="PNG", optimize=True)
        return output.getvalue()

    def _save_sync(self, image: PreparedSafetyImage) -> StoredSafetyImage:
        self._initialize_sync()
        token = uuid.uuid4().hex
        original_name = f"{token}{image.extension}"
        thumbnail_name = f"{token}.thumb{image.extension}"
        original_path = self._safe_path(self.originals, original_name)
        thumbnail_path = self._safe_path(self.thumbnails, thumbnail_name)
        try:
            self._write_private(original_path, image.original_bytes)
            self._write_private(thumbnail_path, image.thumbnail_bytes)
        except Exception:
            original_path.unlink(missing_ok=True)
            thumbnail_path.unlink(missing_ok=True)
            raise
        return StoredSafetyImage(original_name, thumbnail_name)

    def _delete_sync(self, original_storage_name: str, thumbnail_storage_name: str) -> None:
        self._safe_path(self.originals, original_storage_name).unlink(missing_ok=True)
        self._safe_path(self.thumbnails, thumbnail_storage_name).unlink(missing_ok=True)

    @staticmethod
    def _write_private(path: Path, data: bytes) -> None:
        with path.open("xb") as output:
            output.write(data)
        try:
            os.chmod(path, 0o600)
        except OSError:
            pass

    @staticmethod
    def _safe_path(directory: Path, storage_name: str) -> Path:
        if Path(storage_name).name != storage_name:
            raise ValueError("invalid private storage name")
        candidate = (directory / storage_name).resolve()
        if candidate.parent != directory.resolve():
            raise ValueError("private storage path escapes its root")
        return candidate
