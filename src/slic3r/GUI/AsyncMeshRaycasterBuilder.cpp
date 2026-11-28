#include "libslic3r/libslic3r.h"

#include "AsyncMeshRaycasterBuilder.hpp"

#include <boost/log/trivial.hpp>

#include <chrono>

namespace Slic3r {
namespace GUI {

AsyncMeshRaycasterBuilder::AsyncMeshRaycasterBuilder()
{
}

AsyncMeshRaycasterBuilder::~AsyncMeshRaycasterBuilder()
{
    shutdown(false);
}

AsyncMeshRaycasterBuilder& AsyncMeshRaycasterBuilder::instance()
{
    static AsyncMeshRaycasterBuilder instance;
    return instance;
}

void AsyncMeshRaycasterBuilder::init(int num_threads)
{
    if (!m_workers.empty()) {
        shutdown();
    }

    if (num_threads <= 0) {
        unsigned int hw_threads = std::thread::hardware_concurrency();
        num_threads = hw_threads > 1 ? hw_threads - 1 : 1;
    }

    m_shutdown_flag = false;

    for (int i = 0; i < num_threads; ++i) {
        m_workers.emplace_back(&AsyncMeshRaycasterBuilder::worker_thread_func, this);
    }

    BOOST_LOG_TRIVIAL(info) << u8"AsyncMeshRaycasterBuilder started with " << num_threads << u8" worker threads";
}

void AsyncMeshRaycasterBuilder::shutdown(bool log_messages)
{
    m_shutdown_flag = true;
    m_cv.notify_all();

    for (auto& worker : m_workers) {
        if (worker.joinable()) {
            worker.join();
        }
    }
    m_workers.clear();

    {
        std::lock_guard<std::mutex> lock(m_queue_mutex);
        while (!m_pending_tasks.empty()) {
            m_pending_tasks.pop();
        }
    }

    {
        std::lock_guard<std::mutex> lock(m_completed_mutex);
        m_completed_tasks.clear();
    }

    if (log_messages) {
        BOOST_LOG_TRIVIAL(info) << u8"AsyncMeshRaycasterBuilder closed";
    }
}

int AsyncMeshRaycasterBuilder::submit_build_task(
    std::shared_ptr<const TriangleMesh> mesh,
    BuildCallback callback,
    int priority)
{
    if (m_shutdown_flag.load(std::memory_order_acquire)) {
        BOOST_LOG_TRIVIAL(warning) << "AsyncMeshRaycasterBuilder::submit_build_task() - rejected, builder is shutting down";
        return -1;
    }

    if (!mesh || !callback) {
        BOOST_LOG_TRIVIAL(warning) << "AsyncMeshRaycasterBuilder::submit_build_task() - Invalid parameters";
        return -1;
    }

    int task_id = m_next_task_id.fetch_add(1);

    BuildTask task;
    task.task_id = task_id;
    task.mesh = mesh;
    task.callback = std::move(callback);
    task.priority = priority;

    {
        std::lock_guard<std::mutex> lock(m_queue_mutex);
        if (m_shutdown_flag.load(std::memory_order_relaxed)) {
            BOOST_LOG_TRIVIAL(warning) << "AsyncMeshRaycasterBuilder::submit_build_task() - rejected after shutdown request";
            return -1;
        }
        m_pending_tasks.push(std::move(task));
    }

    m_cv.notify_one();

    return task_id;
}

// TODO: support cancellation
bool AsyncMeshRaycasterBuilder::cancel_task(int task_id)
{
    std::lock_guard<std::mutex> lock(m_queue_mutex);

    return false;
}

void AsyncMeshRaycasterBuilder::worker_thread_func()
{
    while (!m_shutdown_flag) {
        BuildTask task;

        {
            std::unique_lock<std::mutex> lock(m_queue_mutex);

            m_cv.wait(lock, [this] {
                return m_shutdown_flag || !m_pending_tasks.empty();
            });

            if (m_shutdown_flag && m_pending_tasks.empty()) {
                break;
            }

            if (m_pending_tasks.empty()) {
                continue;
            }

            task = m_pending_tasks.top();
            m_pending_tasks.pop();
        }

        m_active_tasks_count++;
        auto build_start = std::chrono::high_resolution_clock::now();

        std::unique_ptr<MeshRaycaster> raycaster;
        try {
            raycaster = std::make_unique<MeshRaycaster>(task.mesh);
        }
        catch (const std::exception& ex) {
            BOOST_LOG_TRIVIAL(error) << "AsyncMeshRaycasterBuilder - MeshRaycaster failed to build: " << ex.what();
            raycaster = nullptr;
        }

        auto build_end = std::chrono::high_resolution_clock::now();
        auto duration_ms = std::chrono::duration_cast<std::chrono::milliseconds>(build_end - build_start).count();

        if (raycaster && duration_ms > 100) {
            size_t face_count = task.mesh->its.indices.size();
            BOOST_LOG_TRIVIAL(info) << u8"[Async] MeshRaycaster built in: " << duration_ms
                                    << u8" milliseconds (faces: " << face_count << u8")";
        }

        {
            std::lock_guard<std::mutex> lock(m_completed_mutex);
            m_completed_tasks.push_back({
                task.task_id,
                std::move(raycaster),
                std::move(task.callback)
            });
        }

        m_active_tasks_count--;
    }
}

void AsyncMeshRaycasterBuilder::process_completed_tasks()
{
    std::vector<CompletedTask> tasks_to_process;

    {
        std::lock_guard<std::mutex> lock(m_completed_mutex);
        if (m_completed_tasks.empty()) {
            return;
        }
        tasks_to_process.swap(m_completed_tasks);
    }

    for (auto& task : tasks_to_process) {
        if (task.callback) {
            try {
                task.callback(std::move(task.raycaster));
            }
            catch (const std::exception& ex) {
                BOOST_LOG_TRIVIAL(error) << "AsyncMeshRaycasterBuilder - Callback function failed: " << ex.what();
            }
        }
    }
}

size_t AsyncMeshRaycasterBuilder::get_pending_tasks_count() const
{
    std::lock_guard<std::mutex> lock(m_queue_mutex);
    return m_pending_tasks.size();
}

size_t AsyncMeshRaycasterBuilder::get_active_tasks_count() const
{
    return m_active_tasks_count.load();
}

bool AsyncMeshRaycasterBuilder::has_completed_tasks() const
{
    std::lock_guard<std::mutex> lock(m_completed_mutex);
    return !m_completed_tasks.empty();
}

} // namespace GUI
} // namespace Slic3r


